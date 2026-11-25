use async_trait::async_trait;
use chrono::NaiveDate;
use library::Library;
use manhuagui_ffi::{
    AndroidDownloadObserver, AndroidError, AndroidExportFormat, AndroidLibrary,
    AndroidUpdateWindow, BrowsePage, ChapterItem, ChapterPagesResult, ComicDetails, ComicListItem,
    DownloadedChapter, ImagePage, LocalChapterNavigation, SearchPage,
};
use source_api::{
    BrowseResult, ChapterInfo, ChapterPages, Comic, ComicId, ComicInSearch, ComicSummary,
    CoreConfig, ExportSkipMode, PageRef, ProxyMode, SearchResult, Source, SourceError,
    UpdateWindow,
};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
    sync::{mpsc, watch},
    task::JoinHandle,
};

struct Fixtures {
    browse: Vec<(UpdateWindow, BrowseResult)>,
    search: SearchResult,
    comic: Comic,
    pages: ChapterPages,
}

struct MockSource {
    fixtures: Fixtures,
    failure: Option<SourceError>,
}

impl MockSource {
    fn today() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, 22).unwrap()
    }

    fn new() -> Self {
        let today = Self::today();
        let browse = vec![
            (
                UpdateWindow::Days7,
                browse_result(
                    vec![
                        summary(701, "today", Some(today), Some(9.1)),
                        summary(
                            702,
                            "cutoff",
                            Some(NaiveDate::from_ymd_opt(2026, 11, 15).unwrap()),
                            Some(8.2),
                        ),
                        summary(
                            703,
                            "outside",
                            Some(NaiveDate::from_ymd_opt(2026, 11, 14).unwrap()),
                            None,
                        ),
                        summary(704, "undated", None, None),
                    ],
                    1,
                    6,
                ),
            ),
            (
                UpdateWindow::Days15,
                browse_result(
                    vec![summary(715, "days15 fixture", Some(today), None)],
                    1,
                    4,
                ),
            ),
            (
                UpdateWindow::Days30,
                browse_result(
                    vec![summary(730, "days30 fixture", Some(today), None)],
                    1,
                    5,
                ),
            ),
            (
                UpdateWindow::All,
                browse_result(vec![summary(799, "all fixture", Some(today), None)], 1, 9),
            ),
        ];
        let mut groups = HashMap::new();
        groups.insert(
            "单话".to_owned(),
            vec![ChapterInfo {
                chapter_id: 902,
                chapter_title: "Chapter 2".to_owned(),
                chapter_size: 18,
                comic_id: 901,
                comic_title: "Fixture Comic".to_owned(),
                group_name: "单话".to_owned(),
                group_size: 2,
                order: 2.0,
                comic_status: "连载中".to_owned(),
                ..ChapterInfo::default()
            }],
        );
        let comic = Comic {
            id: 901,
            title: "Fixture Comic".to_owned(),
            subtitle: Some("A fixture subtitle".to_owned()),
            cover: "https://cdn.example/comic.jpg".to_owned(),
            status: "连载中".to_owned(),
            update_time: "2026-11-20".to_owned(),
            year: 2024,
            region: "Japan".to_owned(),
            genres: vec!["Drama".to_owned(), "Mystery".to_owned()],
            authors: vec!["Author".to_owned()],
            aliases: vec!["Alias".to_owned()],
            intro: "Fixture synopsis".to_owned(),
            groups,
            ..Comic::default()
        };
        let mut headers = HashMap::new();
        headers.insert(
            "Referer".to_owned(),
            "https://www.manhuagui.com/".to_owned(),
        );
        headers.insert("X-Fixture".to_owned(), "present".to_owned());

        Self {
            fixtures: Fixtures {
                browse,
                search: SearchResult {
                    comics: vec![ComicInSearch {
                        id: 801,
                        title: "Search Fixture".to_owned(),
                        subtitle: Some("Search subtitle".to_owned()),
                        cover: "https://cdn.example/search.jpg".to_owned(),
                        status: "已完结".to_owned(),
                        update_time: "2 hours ago".to_owned(),
                        year: 2020,
                        region: "Japan".to_owned(),
                        genres: vec!["Adventure".to_owned()],
                        authors: vec!["Search Author".to_owned()],
                        aliases: vec!["Search Alias".to_owned()],
                        intro: "Search synopsis".to_owned(),
                        is_downloaded: false,
                        comic_download_dir: PathBuf::new(),
                    }],
                    current: 4,
                    total: 42,
                },
                comic,
                pages: ChapterPages {
                    pages: vec![PageRef {
                        url: "https://cdn.example/page-1.jpg".to_owned(),
                        headers,
                    }],
                    prev_chapter_id: Some(901),
                    next_chapter_id: Some(903),
                },
            },
            failure: None,
        }
    }

    fn failing(error: SourceError) -> Self {
        Self {
            failure: Some(error),
            ..Self::new()
        }
    }

    fn fail_if_configured(&self) -> Result<(), SourceError> {
        self.failure.clone().map_or(Ok(()), Err)
    }
}

#[async_trait]
impl Source for MockSource {
    fn id(&self) -> &'static str {
        "manhuagui"
    }

    fn today(&self) -> NaiveDate {
        Self::today()
    }

    async fn search(&self, _keyword: &str, _page: i64) -> Result<SearchResult, SourceError> {
        self.fail_if_configured()?;
        Ok(self.fixtures.search.clone())
    }

    async fn browse_latest(
        &self,
        window: UpdateWindow,
        page: i64,
        _genre: Option<&str>,
    ) -> Result<BrowseResult, SourceError> {
        self.fail_if_configured()?;
        let mut result = self
            .fixtures
            .browse
            .iter()
            .find(|(fixture_window, _)| *fixture_window == window)
            .unwrap()
            .1
            .clone();
        result.page = page;
        Ok(result)
    }

    async fn get_comic(&self, _native_id: i64) -> Result<Comic, SourceError> {
        self.fail_if_configured()?;
        Ok(self.fixtures.comic.clone())
    }

    async fn get_chapter_pages(
        &self,
        _native_id: i64,
        _chapter_id: i64,
    ) -> Result<ChapterPages, SourceError> {
        self.fail_if_configured()?;
        Ok(self.fixtures.pages.clone())
    }
}

fn adapter(source: MockSource) -> AndroidLibrary {
    let library = Library::new();
    library.register_source(Arc::new(source));
    AndroidLibrary::with_library(library)
}

fn summary(
    native_id: i64,
    title: &str,
    updated_on: Option<NaiveDate>,
    rating: Option<f32>,
) -> ComicSummary {
    ComicSummary {
        comic_id: ComicId {
            source_id: "manhuagui".to_owned(),
            native_id,
        },
        title: title.to_owned(),
        cover_url: format!("https://cdn.example/{native_id}.jpg"),
        status_line: "更新中".to_owned(),
        updated_on,
        rating,
    }
}

fn browse_result(items: Vec<ComicSummary>, page: i64, total_pages: i64) -> BrowseResult {
    BrowseResult {
        items,
        page,
        total_pages,
    }
}

#[test]
fn source_errors_convert_to_android_error_variants_with_details() {
    let cases = [
        (
            SourceError::Network {
                message: "connection reset".to_owned(),
            },
            AndroidError::Network {
                detail: "connection reset".to_owned(),
            },
        ),
        (
            SourceError::RateLimited {
                message: "retry later".to_owned(),
            },
            AndroidError::RateLimited {
                detail: "retry later".to_owned(),
            },
        ),
        (
            SourceError::ParseFailed {
                message: "invalid response".to_owned(),
            },
            AndroidError::ParseFailed {
                detail: "invalid response".to_owned(),
            },
        ),
        (
            SourceError::NotFound {
                message: "comic missing".to_owned(),
            },
            AndroidError::NotFound {
                detail: "comic missing".to_owned(),
            },
        ),
        (
            SourceError::Cancelled {
                message: "request cancelled".to_owned(),
            },
            AndroidError::Cancelled {
                detail: "request cancelled".to_owned(),
            },
        ),
    ];

    for (source_error, expected) in cases {
        assert_eq!(AndroidError::from(source_error), expected);
    }
}

#[tokio::test]
async fn browse_within_maps_each_android_window_to_the_matching_core_window() {
    let library = adapter(MockSource::new());
    let cases = [
        (AndroidUpdateWindow::Days7, "today"),
        (AndroidUpdateWindow::Days15, "days15 fixture"),
        (AndroidUpdateWindow::Days30, "days30 fixture"),
        (AndroidUpdateWindow::All, "all fixture"),
    ];

    for (window, expected_title) in cases {
        let items = library.browse_within(window, 1, 1).await.unwrap();
        assert_eq!(items[0].title, expected_title);
    }
}

#[tokio::test]
async fn browse_within_keeps_the_inclusive_seven_day_window() {
    let library = adapter(MockSource::new());

    let items = library
        .browse_within(AndroidUpdateWindow::Days7, 1, 1)
        .await
        .unwrap();

    assert_eq!(
        items
            .iter()
            .map(|item| item.title.as_str())
            .collect::<Vec<_>>(),
        vec!["today", "cutoff"]
    );
}

#[tokio::test]
async fn browse_latest_page_maps_items_and_page_metadata() {
    let library = adapter(MockSource::new());

    let page = library.browse_latest_page(3).await.unwrap();

    assert_eq!(
        page,
        BrowsePage {
            items: vec![ComicListItem {
                comic_id: 799,
                title: "all fixture".to_owned(),
                cover_url: "https://cdn.example/799.jpg".to_owned(),
                status_text: "更新中".to_owned(),
                update_text: Some("2026-11-22".to_owned()),
                rating: None,
            }],
            page: 3,
            total_pages: 9,
        }
    );
}

#[tokio::test]
async fn search_maps_results_and_pagination_metadata() {
    let library = adapter(MockSource::new());

    let page = library.search("fixture".to_owned(), 4).await.unwrap();

    assert_eq!(
        page,
        SearchPage {
            items: vec![ComicListItem {
                comic_id: 801,
                title: "Search Fixture".to_owned(),
                cover_url: "https://cdn.example/search.jpg".to_owned(),
                status_text: "已完结".to_owned(),
                update_text: Some("2 hours ago".to_owned()),
                rating: None,
            }],
            current: 4,
            total: 42,
        }
    );
}

#[tokio::test]
async fn get_comic_maps_display_metadata_and_chapters() {
    let library = adapter(MockSource::new());

    let comic = library.get_comic(901).await.unwrap();

    assert_eq!(
        comic,
        ComicDetails {
            comic_id: 901,
            title: "Fixture Comic".to_owned(),
            subtitle: Some("A fixture subtitle".to_owned()),
            cover_url: "https://cdn.example/comic.jpg".to_owned(),
            status_text: "连载中".to_owned(),
            update_text: "2026-11-20".to_owned(),
            year: 2024,
            region: "Japan".to_owned(),
            genres: vec!["Drama".to_owned(), "Mystery".to_owned()],
            authors: vec!["Author".to_owned()],
            aliases: vec!["Alias".to_owned()],
            intro: "Fixture synopsis".to_owned(),
            chapters: vec![ChapterItem {
                chapter_id: 902,
                chapter_title: "Chapter 2".to_owned(),
                chapter_size: 18,
                group_name: "单话".to_owned(),
                group_size: 2,
                order: 2.0,
            }],
        }
    );
}

#[tokio::test]
async fn get_chapter_pages_preserves_page_urls_headers_and_navigation() {
    let library = adapter(MockSource::new());
    let mut headers = HashMap::new();
    headers.insert(
        "Referer".to_owned(),
        "https://www.manhuagui.com/".to_owned(),
    );
    headers.insert("X-Fixture".to_owned(), "present".to_owned());

    let pages = library.get_chapter_pages(901, 902).await.unwrap();

    assert_eq!(
        pages,
        ChapterPagesResult {
            pages: vec![ImagePage {
                url: "https://cdn.example/page-1.jpg".to_owned(),
                headers,
            }],
            prev_chapter_id: Some(901),
            next_chapter_id: Some(903),
        }
    );
}

#[tokio::test]
async fn source_errors_reach_android_callers_with_their_variant_and_detail() {
    let library = adapter(MockSource::failing(SourceError::RateLimited {
        message: "wait before retrying".to_owned(),
    }));

    let error = library.search("fixture".to_owned(), 1).await.unwrap_err();

    assert_eq!(
        error,
        AndroidError::RateLimited {
            detail: "wait before retrying".to_owned(),
        }
    );
}

static NEXT_TEMP_DIR: AtomicU64 = AtomicU64::new(0);

struct TestDir(PathBuf);

impl TestDir {
    fn new() -> Self {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let id = NEXT_TEMP_DIR.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!("manhuagui-ffi-{nonce}-{id}"));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

struct ImageServer {
    url: String,
    requests: mpsc::UnboundedReceiver<()>,
    release: watch::Sender<bool>,
    task: JoinHandle<()>,
}

impl ImageServer {
    async fn start(blocked: bool) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (request_tx, requests) = mpsc::unbounded_channel();
        let (release, release_rx) = watch::channel(false);
        let task = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    return;
                };
                let request_tx = request_tx.clone();
                let mut release_rx = release_rx.clone();
                tokio::spawn(async move {
                    let mut request = [0; 1024];
                    let _ = stream.read(&mut request).await;
                    let _ = request_tx.send(());
                    if blocked {
                        while !*release_rx.borrow() {
                            if release_rx.changed().await.is_err() {
                                return;
                            }
                        }
                    }
                    let image = include_bytes!("fixtures/1x1.jpg");
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        image.len()
                    );
                    if stream.write_all(response.as_bytes()).await.is_ok() {
                        let _ = stream.write_all(image).await;
                    }
                });
            }
        });
        Self {
            url: format!("http://{address}/image.jpg"),
            requests,
            release,
            task,
        }
    }

    async fn wait_for_request(&mut self) {
        self.requests.recv().await.unwrap();
    }

    fn unblock(&self) {
        let _ = self.release.send(true);
    }
}

impl Drop for ImageServer {
    fn drop(&mut self) {
        self.task.abort();
        self.unblock();
    }
}

#[derive(Clone, Default)]
struct ProgressObserver {
    updates: Arc<Mutex<Vec<(i64, i64, u32, u32)>>>,
}

impl AndroidDownloadObserver for ProgressObserver {
    fn on_progress(&self, comic_id: i64, chapter_id: i64, downloaded_pages: u32, total_pages: u32) {
        self.updates
            .lock()
            .unwrap()
            .push((comic_id, chapter_id, downloaded_pages, total_pages));
    }
}

struct DropTrackedObserver(Arc<AtomicBool>);

impl AndroidDownloadObserver for DropTrackedObserver {
    fn on_progress(
        &self,
        _comic_id: i64,
        _chapter_id: i64,
        _downloaded_pages: u32,
        _total_pages: u32,
    ) {
    }
}

impl Drop for DropTrackedObserver {
    fn drop(&mut self) {
        self.0.store(true, Ordering::SeqCst);
    }
}

fn test_config(download_root: &Path, export_root: &Path) -> CoreConfig {
    CoreConfig {
        download_dir: download_root.to_path_buf(),
        export_dir: export_root.to_path_buf(),
        comic_dir_fmt: "{comic_id}".to_owned(),
        chapter_dir_fmt: "{chapter_id}".to_owned(),
        chapter_download_interval_sec: 0,
        img_download_interval_sec: 0,
        api_timeout_sec: 15,
        img_timeout_sec: 30,
        proxy_mode: ProxyMode::System,
        export_skip_mode: ExportSkipMode::None,
        ..CoreConfig::default()
    }
}

fn add_chapter(comic: &mut Comic, id: i64, group: &str, group_size: i64, order: f64) {
    comic
        .groups
        .entry(group.to_owned())
        .or_default()
        .push(ChapterInfo {
            chapter_id: id,
            chapter_title: format!("Chapter {id}"),
            chapter_size: 1,
            comic_id: comic.id,
            comic_title: comic.title.clone(),
            group_name: group.to_owned(),
            group_size,
            order,
            comic_status: comic.status.clone(),
            ..ChapterInfo::default()
        });
}

fn set_image_pages(source: &mut MockSource, image_url: &str, count: usize) {
    source.fixtures.pages.pages = (0..count)
        .map(|_| PageRef {
            url: image_url.to_owned(),
            headers: HashMap::new(),
        })
        .collect();
}

fn save_comic_metadata(comic: &mut Comic, download_root: &Path) {
    comic
        .ensure_download_dir_fields(&test_config(download_root, download_root))
        .unwrap();
    comic.save_metadata().unwrap();
}

fn seed_downloaded_chapters(comic: &mut Comic, download_root: &Path, chapter_ids: &[i64]) {
    comic
        .ensure_download_dir_fields(&test_config(download_root, download_root))
        .unwrap();
    for chapter in comic.groups.values_mut().flatten() {
        if !chapter_ids.contains(&chapter.chapter_id) {
            continue;
        }
        chapter.is_downloaded = Some(true);
        let chapter_dir = chapter.chapter_download_dir.as_ref().unwrap();
        fs::create_dir_all(chapter_dir).unwrap();
        fs::write(
            chapter_dir.join("001.jpg"),
            include_bytes!("fixtures/1x1.jpg"),
        )
        .unwrap();
        chapter.save_metadata().unwrap();
    }
    comic.save_metadata().unwrap();
}

#[tokio::test]
async fn download_chapter_saves_images_and_preserves_existing_chapter_metadata() {
    let root = TestDir::new();
    let mut server = ImageServer::start(false).await;
    let mut source = MockSource::new();
    add_chapter(&mut source.fixtures.comic, 901, "单话", 2, 1.0);
    set_image_pages(&mut source, &server.url, 2);
    let comic_root = root.path().join("901");
    let mut comic = source.fixtures.comic.clone();
    seed_downloaded_chapters(&mut comic, root.path(), &[901]);
    let existing_metadata = comic_root.join("901").join("章节元数据.json");
    let expected_existing_metadata = fs::read(&existing_metadata).unwrap();
    let library = Arc::new(adapter(source));
    let observer = ProgressObserver::default();
    let download_root = root.path().to_string_lossy().into_owned();
    let download = {
        let library = library.clone();
        let observer = observer.clone();
        tokio::spawn(async move {
            library
                .download_chapter(901, 902, download_root, Box::new(observer))
                .await
        })
    };
    for _ in 0..2 {
        tokio::time::timeout(Duration::from_secs(2), server.wait_for_request())
            .await
            .unwrap();
    }
    let downloaded = download.await.unwrap().unwrap_or_else(|error| {
        panic!(
            "download failed with {error:?}; progress={:?}",
            observer.updates.lock().unwrap()
        )
    });

    assert_eq!(
        downloaded,
        DownloadedChapter {
            comic_id: 901,
            comic_title: "Fixture Comic".to_owned(),
            chapter_id: 902,
            chapter_title: "Chapter 2".to_owned(),
            group_name: "单话".to_owned(),
            order: 2.0,
            page_count: 2,
            chapter_dir: root
                .path()
                .join("901")
                .join("902")
                .to_string_lossy()
                .into_owned(),
        }
    );
    assert!(root.path().join("901").join("元数据.json").exists());
    assert!(root
        .path()
        .join("901")
        .join("902")
        .join("章节元数据.json")
        .exists());
    assert!(root.path().join("901").join("902").join("001.jpg").exists());
    assert!(root.path().join("901").join("902").join("002.jpg").exists());
    assert_eq!(
        fs::read(existing_metadata).unwrap(),
        expected_existing_metadata
    );
    let updates = observer.updates.lock().unwrap();
    assert!(updates
        .iter()
        .any(|update| update.0 == 901 && update.1 == 902 && update.2 == 0));
    assert!(updates
        .iter()
        .any(|update| update.0 == 901 && update.1 == 902 && update.2 == 2 && update.3 == 2));
    server.unblock();
}

#[tokio::test]
async fn download_releases_the_foreign_observer_after_completion() {
    let root = TestDir::new();
    let server = ImageServer::start(false).await;
    let mut source = MockSource::new();
    set_image_pages(&mut source, &server.url, 1);
    let library = adapter(source);
    let dropped = Arc::new(AtomicBool::new(false));

    library
        .download_chapter(
            901,
            902,
            root.path().to_string_lossy().into_owned(),
            Box::new(DropTrackedObserver(dropped.clone())),
        )
        .await
        .unwrap();

    assert!(dropped.load(Ordering::SeqCst));
    server.unblock();
}

#[tokio::test]
async fn scan_downloaded_chapters_removes_interrupted_directories_and_returns_files() {
    let root = TestDir::new();
    let source = MockSource::new();
    let mut comic = source.fixtures.comic.clone();
    seed_downloaded_chapters(&mut comic, root.path(), &[902]);
    let interrupted = root.path().join("901").join(".下载中-903");
    fs::create_dir_all(&interrupted).unwrap();
    fs::write(interrupted.join("partial.jpg"), [1, 2, 3]).unwrap();
    let library = adapter(source);

    assert!(library
        .scan_downloaded_chapters(root.path().join("missing").to_string_lossy().into_owned())
        .await
        .unwrap()
        .is_empty());
    let chapters = library
        .scan_downloaded_chapters(root.path().to_string_lossy().into_owned())
        .await
        .unwrap();

    assert!(!interrupted.exists());
    assert_eq!(
        chapters,
        vec![DownloadedChapter {
            comic_id: 901,
            comic_title: "Fixture Comic".to_owned(),
            chapter_id: 902,
            chapter_title: "Chapter 2".to_owned(),
            group_name: "单话".to_owned(),
            order: 2.0,
            page_count: 1,
            chapter_dir: root
                .path()
                .join("901")
                .join("902")
                .to_string_lossy()
                .into_owned(),
        }]
    );
}

#[tokio::test]
async fn read_local_chapter_navigation_uses_group_and_order_sorting() {
    let root = TestDir::new();
    let mut source = MockSource::new();
    add_chapter(&mut source.fixtures.comic, 901, "单话", 2, 1.0);
    add_chapter(&mut source.fixtures.comic, 903, "番外", 1, 1.0);
    let mut comic = source.fixtures.comic.clone();
    save_comic_metadata(&mut comic, root.path());
    let library = adapter(source);

    let navigation = library
        .read_local_chapter_navigation(901, 902, root.path().to_string_lossy().into_owned())
        .await
        .unwrap();

    assert_eq!(
        navigation,
        LocalChapterNavigation {
            prev_chapter_id: Some(901),
            next_chapter_id: Some(903),
        }
    );
}

#[tokio::test]
async fn export_chapters_writes_cbz_and_pdf_in_requested_order() {
    let root = TestDir::new();
    let download_root = root.path().join("downloads");
    let export_root = root.path().join("exports");
    let mut source = MockSource::new();
    add_chapter(&mut source.fixtures.comic, 901, "单话", 2, 1.0);
    let mut comic = source.fixtures.comic.clone();
    seed_downloaded_chapters(&mut comic, &download_root, &[901, 902]);
    let library = adapter(source);
    let chapter_ids = vec![902, 901];

    for format in [AndroidExportFormat::Cbz, AndroidExportFormat::Pdf] {
        let files = library
            .export_chapters(
                901,
                chapter_ids.clone(),
                download_root.to_string_lossy().into_owned(),
                export_root.to_string_lossy().into_owned(),
                format,
            )
            .await
            .unwrap();
        let extension = match format {
            AndroidExportFormat::Cbz => "cbz",
            AndroidExportFormat::Pdf => "pdf",
        };

        assert_eq!(
            files.iter().map(|file| file.chapter_id).collect::<Vec<_>>(),
            chapter_ids
        );
        for file in files {
            let expected_path = export_root
                .join("901")
                .join(extension)
                .join(format!("{}.{}", file.chapter_id, extension));
            assert_eq!(file.file_path, expected_path.to_string_lossy().into_owned());
            let chapter_title = match file.chapter_id {
                902 => "Chapter 2",
                901 => "Chapter 901",
                _ => unreachable!(),
            };
            assert_eq!(
                file.file_name,
                format!("Fixture Comic - {chapter_title}.{extension}")
            );
            assert!(expected_path.is_file());
        }
    }
}

#[tokio::test]
async fn delete_downloaded_chapter_removes_chapter_and_empty_comic_directory() {
    let root = TestDir::new();
    let mut source = MockSource::new();
    add_chapter(&mut source.fixtures.comic, 901, "单话", 2, 1.0);
    let mut comic = source.fixtures.comic.clone();
    seed_downloaded_chapters(&mut comic, root.path(), &[901, 902]);
    let comic_root = root.path().join("901");
    let library = adapter(source);
    let download_root = root.path().to_string_lossy().into_owned();

    library
        .delete_downloaded_chapter(901, 902, download_root.clone())
        .await
        .unwrap();

    assert!(comic_root.is_dir());
    assert!(comic_root.join("901").join("章节元数据.json").is_file());
    library
        .delete_downloaded_chapter(901, 901, download_root)
        .await
        .unwrap();

    assert!(!comic_root.exists());
}

#[tokio::test]
async fn delete_downloaded_chapter_preserves_comic_directory_with_active_download() {
    let root = TestDir::new();
    let source = MockSource::new();
    let mut comic = source.fixtures.comic.clone();
    seed_downloaded_chapters(&mut comic, root.path(), &[902]);
    let comic_root = root.path().join("901");
    let in_progress = comic_root.join(".下载中-903");
    fs::create_dir_all(&in_progress).unwrap();
    fs::write(in_progress.join("partial.jpg"), [1, 2, 3]).unwrap();
    let library = adapter(source);

    library
        .delete_downloaded_chapter(901, 902, root.path().to_string_lossy().into_owned())
        .await
        .unwrap();

    assert!(!comic_root.join("902").exists());
    assert!(comic_root.is_dir());
    assert!(in_progress.is_dir());
    assert!(in_progress.join("partial.jpg").is_file());
}

#[tokio::test]
async fn download_chapter_rejects_empty_chapters_and_removes_download_directories() {
    let root = TestDir::new();
    let mut source = MockSource::new();
    source.fixtures.pages.pages.clear();
    let library = adapter(source);

    let result = library
        .download_chapter(
            901,
            902,
            root.path().to_string_lossy().into_owned(),
            Box::new(ProgressObserver::default()),
        )
        .await;

    assert!(matches!(
        result,
        Err(AndroidError::Network { detail }) if detail == "Chapter has no pages"
    ));
    assert!(!root.path().join("901").join(".下载中-902").exists());
    assert!(!root.path().join("901").join("902").exists());
}

#[tokio::test]
async fn cancel_download_stops_in_flight_work_and_removes_temporary_directory() {
    let root = TestDir::new();
    let mut server = ImageServer::start(true).await;
    let mut source = MockSource::new();
    set_image_pages(&mut source, &server.url, 1);
    let library = Arc::new(adapter(source));
    let download_root = root.path().to_string_lossy().into_owned();
    let download = {
        let library = library.clone();
        tokio::spawn(async move {
            library
                .download_chapter(
                    901,
                    902,
                    download_root,
                    Box::new(ProgressObserver::default()),
                )
                .await
        })
    };
    tokio::time::timeout(Duration::from_secs(2), server.wait_for_request())
        .await
        .unwrap();
    let temporary_dir = root.path().join("901").join(".下载中-902");
    assert!(temporary_dir.exists());

    library.cancel_download(902);
    let result = download.await.unwrap();

    assert!(matches!(result, Err(AndroidError::Cancelled { .. })));
    assert!(!temporary_dir.exists());
    server.unblock();
}

#[tokio::test]
async fn duplicate_download_is_rejected_without_replacing_the_active_cancellation_sender() {
    let root = TestDir::new();
    let mut server = ImageServer::start(true).await;
    let mut source = MockSource::new();
    set_image_pages(&mut source, &server.url, 1);
    let library = Arc::new(adapter(source));
    let download_root = root.path().to_string_lossy().into_owned();
    let mut first = {
        let library = library.clone();
        let download_root = download_root.clone();
        tokio::spawn(async move {
            library
                .download_chapter(
                    901,
                    902,
                    download_root,
                    Box::new(ProgressObserver::default()),
                )
                .await
        })
    };
    tokio::time::timeout(Duration::from_secs(2), server.wait_for_request())
        .await
        .unwrap();
    let temporary_dir = root.path().join("901").join(".下载中-902");
    assert!(temporary_dir.exists());

    let mut second = {
        let library = library.clone();
        tokio::spawn(async move {
            library
                .download_chapter(
                    901,
                    902,
                    download_root,
                    Box::new(ProgressObserver::default()),
                )
                .await
        })
    };
    let second_result_while_blocked =
        tokio::time::timeout(Duration::from_secs(2), &mut second).await;
    let second_was_rejected = matches!(
        &second_result_while_blocked,
        Ok(Ok(Err(AndroidError::Cancelled { detail })))
            if detail == "Chapter 902 download is already active"
    );

    library.cancel_download(902);
    let first_result_while_blocked = tokio::time::timeout(Duration::from_secs(2), &mut first).await;
    server.unblock();
    let second_result = match second_result_while_blocked {
        Ok(result) => result.unwrap(),
        Err(_) => second.await.unwrap(),
    };
    let first_result = match first_result_while_blocked {
        Ok(result) => result.unwrap(),
        Err(_) => first.await.unwrap(),
    };

    assert!(second_was_rejected);
    assert!(matches!(second_result, Err(AndroidError::Cancelled { .. })));
    assert!(matches!(first_result, Err(AndroidError::Cancelled { .. })));
    assert!(!temporary_dir.exists());
}

#[tokio::test]
async fn dropping_download_future_cancels_the_task_and_removes_temporary_files() {
    let root = TestDir::new();
    let mut server = ImageServer::start(true).await;
    let mut source = MockSource::new();
    set_image_pages(&mut source, &server.url, 1);
    let library = Arc::new(adapter(source));
    let download_root = root.path().to_string_lossy().into_owned();
    let download = {
        let library = library.clone();
        tokio::spawn(async move {
            library
                .download_chapter(
                    901,
                    902,
                    download_root,
                    Box::new(ProgressObserver::default()),
                )
                .await
        })
    };
    tokio::time::timeout(Duration::from_secs(2), server.wait_for_request())
        .await
        .unwrap();
    let temporary_dir = root.path().join("901").join(".下载中-902");
    assert!(temporary_dir.exists());

    download.abort();
    assert!(download.await.unwrap_err().is_cancelled());

    assert!(!temporary_dir.exists());
    server.unblock();
}
