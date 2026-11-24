use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

use async_trait::async_trait;
use library::{
    downloader::{
        download_task::DownloadTask, download_task_state::DownloadTaskState, DownloadDeps,
    },
    events::{TaskUpdatePayload, EVENT_TASK_UPDATE},
    Library,
};
use parking_lot::{Mutex, RwLock};
use reqwest_middleware::ClientWithMiddleware;
use reqwest_retry::{policies::ExponentialBackoff, RetryTransientMiddleware};
use source_api::{
    ChapterInfo, Comic, CoreConfig, EventObserver, ExportSkipMode, LibraryEvent, PageFetcher,
    PageRef, ProxyMode, Source, UpdateWindow,
};
use source_manhuagui::{ManhuaguiClient, ManhuaguiSource};
use tokio::sync::{watch, Semaphore};
use walkdir::{DirEntry, WalkDir};

use crate::types::{
    AndroidDownloadObserver, AndroidError, AndroidExportFormat, AndroidUpdateWindow, BrowsePage,
    ChapterPagesResult, ComicDetails, ComicListItem, DownloadedChapter, ExportedChapterFile,
    LocalChapterNavigation, SearchPage,
};

#[derive(uniffi::Object)]
pub struct AndroidLibrary {
    inner: Library,
    active_downloads: Mutex<HashMap<i64, watch::Sender<()>>>,
    img_client: Arc<RwLock<ClientWithMiddleware>>,
    chapter_sem: Arc<Semaphore>,
    img_sem: Arc<Semaphore>,
    byte_per_sec: Arc<AtomicU64>,
}

impl AndroidLibrary {
    pub fn with_library(library: Library) -> Self {
        let config = android_core_config("", "");
        let resources = shared_download_resources(&config);
        Self {
            inner: library,
            active_downloads: Mutex::new(HashMap::new()),
            img_client: resources.img_client,
            chapter_sem: resources.chapter_sem,
            img_sem: resources.img_sem,
            byte_per_sec: resources.byte_per_sec,
        }
    }

    fn source(&self) -> Result<Arc<dyn Source>, AndroidError> {
        self.inner
            .source("manhuagui")
            .ok_or_else(|| AndroidError::NotFound {
                detail: "Manhuagui source is not registered".to_owned(),
            })
    }
}

fn android_core_config(download_root: &str, export_root: &str) -> CoreConfig {
    CoreConfig {
        download_dir: PathBuf::from(download_root),
        export_dir: PathBuf::from(export_root),
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

struct SharedDownloadResources {
    img_client: Arc<RwLock<ClientWithMiddleware>>,
    chapter_sem: Arc<Semaphore>,
    img_sem: Arc<Semaphore>,
    byte_per_sec: Arc<AtomicU64>,
}

fn shared_download_resources(config: &CoreConfig) -> SharedDownloadResources {
    let client = reqwest::ClientBuilder::new()
        .timeout(Duration::from_secs(config.img_timeout_sec))
        .build()
        .unwrap();
    let retry_policy = ExponentialBackoff::builder().build_with_max_retries(3);
    let img_client = reqwest_middleware::ClientBuilder::new(client)
        .with(RetryTransientMiddleware::new_with_policy(retry_policy))
        .build();

    SharedDownloadResources {
        img_client: Arc::new(RwLock::new(img_client)),
        chapter_sem: Arc::new(Semaphore::new(config.chapter_concurrency)),
        img_sem: Arc::new(Semaphore::new(config.img_concurrency)),
        byte_per_sec: Arc::new(AtomicU64::new(0)),
    }
}

fn parse_error(error: impl std::fmt::Display) -> AndroidError {
    AndroidError::ParseFailed {
        detail: error.to_string(),
    }
}

fn walk_entries(root: &Path) -> impl Iterator<Item = Result<DirEntry, walkdir::Error>> {
    WalkDir::new(root).into_iter()
}

fn count_jpg_files(chapter_dir: &Path) -> Result<usize, AndroidError> {
    let mut count = 0;
    for entry in fs::read_dir(chapter_dir).map_err(parse_error)? {
        let entry = entry.map_err(parse_error)?;
        if entry.file_type().map_err(parse_error)?.is_file()
            && entry
                .path()
                .extension()
                .and_then(|extension| extension.to_str())
                .is_some_and(|extension| extension.eq_ignore_ascii_case("jpg"))
        {
            count += 1;
        }
    }
    Ok(count)
}

fn remove_directory(path: &Path) -> Result<(), AndroidError> {
    if path.exists() {
        fs::remove_dir_all(path).map_err(parse_error)?;
    }
    Ok(())
}

struct SourcePageFetcher {
    source: Arc<dyn Source>,
}

#[async_trait]
impl PageFetcher for SourcePageFetcher {
    async fn fetch_pages(&self, chapter_info: &ChapterInfo) -> eyre::Result<Vec<PageRef>> {
        self.source
            .get_chapter_pages(chapter_info.comic_id, chapter_info.chapter_id)
            .await
            .map(|pages| pages.pages)
            .map_err(eyre::Report::from)
    }
}

struct AndroidEventObserver {
    observer: Mutex<Option<Arc<dyn AndroidDownloadObserver>>>,
    comic_id: i64,
    chapter_id: i64,
    last_emitted: Mutex<Instant>,
}

impl AndroidEventObserver {
    fn disconnect(&self) {
        self.observer.lock().take();
    }
}

struct AndroidObserverDisconnect(Arc<AndroidEventObserver>);

impl Drop for AndroidObserverDisconnect {
    fn drop(&mut self) {
        self.0.disconnect();
    }
}

impl EventObserver for AndroidEventObserver {
    fn on_event(&self, event: &LibraryEvent) {
        if event.name != EVENT_TASK_UPDATE {
            return;
        }
        let Ok(payload) = serde_json::from_value::<TaskUpdatePayload>(event.payload.clone()) else {
            return;
        };
        if payload.chapter_id != self.chapter_id {
            return;
        }

        let observer = self.observer.lock();
        let Some(observer) = observer.as_ref() else {
            return;
        };
        let terminal =
            payload.downloaded_img_count == payload.total_img_count && payload.total_img_count > 0;
        let mut last_emitted = self.last_emitted.lock();
        if payload.downloaded_img_count == 0
            || terminal
            || last_emitted.elapsed() >= Duration::from_millis(250)
        {
            *last_emitted = Instant::now();
            observer.on_progress(
                self.comic_id,
                self.chapter_id,
                payload.downloaded_img_count,
                payload.total_img_count,
            );
        }
    }
}

struct NoopObserver;

impl EventObserver for NoopObserver {
    fn on_event(&self, _event: &LibraryEvent) {}
}

struct DownloadRegistration<'a> {
    active_downloads: &'a Mutex<HashMap<i64, watch::Sender<()>>>,
    chapter_id: i64,
    delete_sender: watch::Sender<()>,
    reservation_receiver: watch::Receiver<()>,
    temp_dir: Option<PathBuf>,
    finished: bool,
}

impl<'a> DownloadRegistration<'a> {
    fn new(
        active_downloads: &'a Mutex<HashMap<i64, watch::Sender<()>>>,
        chapter_id: i64,
    ) -> Result<Self, AndroidError> {
        let (reservation_sender, reservation_receiver) = watch::channel(());
        let mut active_entries = active_downloads.lock();
        if active_entries.contains_key(&chapter_id) {
            return Err(AndroidError::Cancelled {
                detail: format!("Chapter {chapter_id} download is already active"),
            });
        }
        active_entries.insert(chapter_id, reservation_sender.clone());
        drop(active_entries);

        Ok(Self {
            active_downloads,
            chapter_id,
            delete_sender: reservation_sender,
            reservation_receiver,
            temp_dir: None,
            finished: false,
        })
    }

    fn replace_sender(
        &mut self,
        delete_sender: watch::Sender<()>,
        temp_dir: PathBuf,
    ) -> Result<(), AndroidError> {
        self.temp_dir = Some(temp_dir);
        let mut active_downloads = self.active_downloads.lock();
        let reservation_is_active = active_downloads
            .get(&self.chapter_id)
            .is_some_and(|sender| sender.same_channel(&self.delete_sender));
        if !reservation_is_active || self.reservation_receiver.has_changed().unwrap_or(true) {
            drop(active_downloads);
            delete_sender.send_replace(());
            return Err(AndroidError::Cancelled {
                detail: format!("Cancelled chapter {}", self.chapter_id),
            });
        }
        active_downloads.insert(self.chapter_id, delete_sender.clone());
        self.delete_sender = delete_sender;
        Ok(())
    }

    fn finish(&mut self) {
        self.finished = true;
        self.unregister();
    }

    fn unregister(&self) {
        let mut active_downloads = self.active_downloads.lock();
        if active_downloads
            .get(&self.chapter_id)
            .is_some_and(|sender| sender.same_channel(&self.delete_sender))
        {
            active_downloads.remove(&self.chapter_id);
        }
    }
}

impl Drop for DownloadRegistration<'_> {
    fn drop(&mut self) {
        if !self.finished {
            self.delete_sender.send_replace(());
            if let Some(temp_dir) = &self.temp_dir {
                let _ = fs::remove_dir_all(temp_dir);
            }
        }
        self.unregister();
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl AndroidLibrary {
    #[uniffi::constructor]
    pub fn new() -> Self {
        let mut config = CoreConfig::default();
        config.proxy_mode = ProxyMode::System;
        config.api_timeout_sec = 15;
        config.img_timeout_sec = 30;

        let source = ManhuaguiSource::new(ManhuaguiClient::new(&config));
        let inner = Library::new();
        inner.register_source(Arc::new(source));
        let resources = shared_download_resources(&config);
        Self {
            inner,
            active_downloads: Mutex::new(HashMap::new()),
            img_client: resources.img_client,
            chapter_sem: resources.chapter_sem,
            img_sem: resources.img_sem,
            byte_per_sec: resources.byte_per_sec,
        }
    }

    pub async fn browse_within(
        &self,
        window: AndroidUpdateWindow,
        start_page: i32,
        max_pages: i32,
    ) -> Result<Vec<ComicListItem>, AndroidError> {
        let source = self.source()?;
        library::browse_within(
            source.as_ref(),
            UpdateWindow::from(window),
            i64::from(start_page),
            i64::from(max_pages),
        )
        .await
        .map(|items| items.into_iter().map(Into::into).collect())
        .map_err(Into::into)
    }

    pub async fn browse_latest_page(&self, page: i32) -> Result<BrowsePage, AndroidError> {
        let source = self.source()?;
        let result = source
            .browse_latest(UpdateWindow::All, i64::from(page), None)
            .await
            .map_err(AndroidError::from)?;
        Ok(BrowsePage {
            items: result.items.into_iter().map(Into::into).collect(),
            page: result.page,
            total_pages: result.total_pages,
        })
    }

    pub async fn search(&self, keyword: String, page: i32) -> Result<SearchPage, AndroidError> {
        let source = self.source()?;
        let result = source
            .search(&keyword, i64::from(page))
            .await
            .map_err(AndroidError::from)?;
        Ok(SearchPage {
            items: result.comics.into_iter().map(Into::into).collect(),
            current: result.current,
            total: result.total,
        })
    }

    pub async fn get_comic(&self, native_id: i64) -> Result<ComicDetails, AndroidError> {
        self.source()?
            .get_comic(native_id)
            .await
            .map(Into::into)
            .map_err(Into::into)
    }

    pub async fn get_chapter_pages(
        &self,
        native_id: i64,
        chapter_id: i64,
    ) -> Result<ChapterPagesResult, AndroidError> {
        self.source()?
            .get_chapter_pages(native_id, chapter_id)
            .await
            .map(Into::into)
            .map_err(Into::into)
    }

    pub async fn download_chapter(
        &self,
        native_id: i64,
        chapter_id: i64,
        download_root: String,
        observer: Box<dyn AndroidDownloadObserver>,
    ) -> Result<DownloadedChapter, AndroidError> {
        self.download_chapter_with_observer(
            native_id,
            chapter_id,
            download_root,
            Arc::from(observer),
        )
        .await
    }

    pub fn cancel_download(&self, chapter_id: i64) {
        if let Some(delete_sender) = self.active_downloads.lock().remove(&chapter_id) {
            delete_sender.send_replace(());
        }
    }

    pub async fn delete_downloaded_chapter(
        &self,
        native_id: i64,
        chapter_id: i64,
        download_root: String,
    ) -> Result<(), AndroidError> {
        self.cancel_download(chapter_id);
        let comic_dir = PathBuf::from(download_root).join(native_id.to_string());
        remove_directory(&comic_dir.join(chapter_id.to_string()))?;
        remove_directory(&comic_dir.join(format!(".下载中-{chapter_id}")))?;

        if comic_dir.is_dir() {
            let mut has_chapter_metadata = false;
            for entry in walk_entries(&comic_dir) {
                let entry = entry.map_err(parse_error)?;
                if entry.file_type().is_file()
                    && entry.file_name().to_string_lossy() == "章节元数据.json"
                    && entry
                        .path()
                        .parent()
                        .is_some_and(|parent| parent != comic_dir.as_path())
                {
                    has_chapter_metadata = true;
                    break;
                }
            }
            if !has_chapter_metadata {
                remove_directory(&comic_dir)?;
            }
        }
        Ok(())
    }

    pub async fn scan_downloaded_chapters(
        &self,
        download_root: String,
    ) -> Result<Vec<DownloadedChapter>, AndroidError> {
        let root = PathBuf::from(download_root);
        if !root.exists() {
            return Ok(Vec::new());
        }

        let mut temp_dirs = Vec::new();
        for entry in walk_entries(&root) {
            let entry = entry.map_err(parse_error)?;
            if entry.file_type().is_dir()
                && entry.file_name().to_string_lossy().starts_with(".下载中-")
            {
                temp_dirs.push(entry.path().to_path_buf());
            }
        }
        for temp_dir in temp_dirs {
            remove_directory(&temp_dir)?;
        }

        let mut downloaded = Vec::new();
        for entry in walk_entries(&root) {
            let entry = entry.map_err(parse_error)?;
            if !entry.file_type().is_file() || entry.file_name().to_string_lossy() != "元数据.json"
            {
                continue;
            }
            let comic = Comic::from_metadata(entry.path()).map_err(parse_error)?;
            for chapter in comic.groups.values().flatten() {
                if chapter.is_downloaded != Some(true) {
                    continue;
                }
                let Some(chapter_dir) = chapter.chapter_download_dir.as_ref() else {
                    continue;
                };
                if !chapter_dir.is_dir() {
                    continue;
                }
                let page_count = count_jpg_files(chapter_dir)?;
                if page_count == 0 {
                    continue;
                }
                downloaded.push(DownloadedChapter {
                    comic_id: comic.id,
                    comic_title: comic.title.clone(),
                    chapter_id: chapter.chapter_id,
                    chapter_title: chapter.chapter_title.clone(),
                    group_name: chapter.group_name.clone(),
                    order: chapter.order,
                    page_count: i32::try_from(page_count).unwrap_or(i32::MAX),
                    chapter_dir: chapter_dir.to_string_lossy().into_owned(),
                });
            }
        }
        downloaded.sort_by(|left, right| {
            left.comic_id
                .cmp(&right.comic_id)
                .then_with(|| left.group_name.cmp(&right.group_name))
                .then_with(|| left.order.total_cmp(&right.order))
                .then_with(|| left.chapter_id.cmp(&right.chapter_id))
        });
        Ok(downloaded)
    }

    pub async fn read_local_chapter_navigation(
        &self,
        native_id: i64,
        chapter_id: i64,
        download_root: String,
    ) -> Result<LocalChapterNavigation, AndroidError> {
        let metadata_path = PathBuf::from(download_root)
            .join(native_id.to_string())
            .join("元数据.json");
        if !metadata_path.is_file() {
            return Err(AndroidError::NotFound {
                detail: format!("Comic metadata not found at {}", metadata_path.display()),
            });
        }
        let comic = Comic::from_metadata(&metadata_path).map_err(parse_error)?;
        let mut chapters: Vec<&ChapterInfo> = comic.groups.values().flatten().collect();
        chapters.sort_by(|left, right| {
            right
                .group_size
                .cmp(&left.group_size)
                .then_with(|| left.group_name.cmp(&right.group_name))
                .then_with(|| left.order.total_cmp(&right.order))
                .then_with(|| left.chapter_id.cmp(&right.chapter_id))
        });
        let Some(index) = chapters
            .iter()
            .position(|chapter| chapter.chapter_id == chapter_id)
        else {
            return Err(AndroidError::NotFound {
                detail: format!("Chapter {chapter_id} not found in comic {native_id}"),
            });
        };
        Ok(LocalChapterNavigation {
            prev_chapter_id: index
                .checked_sub(1)
                .map(|previous| chapters[previous].chapter_id),
            next_chapter_id: chapters.get(index + 1).map(|chapter| chapter.chapter_id),
        })
    }

    pub async fn export_chapters(
        &self,
        native_id: i64,
        chapter_ids: Vec<i64>,
        download_root: String,
        export_root: String,
        format: AndroidExportFormat,
    ) -> Result<Vec<ExportedChapterFile>, AndroidError> {
        if chapter_ids.is_empty() {
            return Err(AndroidError::NotFound {
                detail: "No chapters requested for export".to_owned(),
            });
        }
        let metadata_path = PathBuf::from(&download_root)
            .join(native_id.to_string())
            .join("元数据.json");
        if !metadata_path.is_file() {
            return Err(AndroidError::NotFound {
                detail: format!("Comic metadata not found at {}", metadata_path.display()),
            });
        }
        let comic = Comic::from_metadata(&metadata_path).map_err(parse_error)?;
        let chapters = chapter_ids
            .iter()
            .map(|chapter_id| {
                comic
                    .groups
                    .values()
                    .flatten()
                    .find(|chapter| chapter.chapter_id == *chapter_id)
                    .filter(|chapter| chapter.is_downloaded == Some(true))
                    .ok_or_else(|| AndroidError::NotFound {
                        detail: format!("Downloaded chapter {chapter_id} was not found"),
                    })
            })
            .collect::<Result<Vec<_>, _>>()?;
        let config = android_core_config(&download_root, &export_root);
        let observer = NoopObserver;
        let extension = match format {
            AndroidExportFormat::Cbz => "cbz",
            AndroidExportFormat::Pdf => "pdf",
        };
        match format {
            AndroidExportFormat::Cbz => {
                library::export::cbz_chapters(&config, &observer, &comic, chapter_ids.clone())
            }
            AndroidExportFormat::Pdf => {
                library::export::pdf_chapters(&config, &observer, &comic, chapter_ids.clone())
            }
        }
        .map_err(parse_error)?;

        chapter_ids
            .into_iter()
            .zip(chapters)
            .map(|(chapter_id, chapter)| {
                let file_path = PathBuf::from(&export_root)
                    .join(native_id.to_string())
                    .join(extension)
                    .join(format!("{chapter_id}.{extension}"));
                if !file_path.is_file() {
                    return Err(AndroidError::NotFound {
                        detail: format!(
                            "Exported chapter file not found at {}",
                            file_path.display()
                        ),
                    });
                }
                Ok(ExportedChapterFile {
                    chapter_id,
                    file_name: format!(
                        "{} - {}.{}",
                        source_api::filename_filter(&comic.title),
                        source_api::filename_filter(&chapter.chapter_title),
                        extension
                    ),
                    file_path: file_path.to_string_lossy().into_owned(),
                })
            })
            .collect()
    }
}

impl AndroidLibrary {
    async fn download_chapter_with_observer(
        &self,
        native_id: i64,
        chapter_id: i64,
        download_root: String,
        observer: Arc<dyn AndroidDownloadObserver>,
    ) -> Result<DownloadedChapter, AndroidError> {
        let mut registration = DownloadRegistration::new(&self.active_downloads, chapter_id)?;
        let source = self.source()?;
        let mut comic = tokio::select! {
            comic = source.get_comic(native_id) => comic.map_err(AndroidError::from)?,
            _ = registration.reservation_receiver.changed() => {
                return Err(AndroidError::Cancelled {
                    detail: format!("Cancelled chapter {chapter_id}"),
                });
            }
        };
        let config = android_core_config(&download_root, &download_root);
        comic
            .ensure_download_dir_fields(&config)
            .map_err(parse_error)?;

        let event_observer = Arc::new(AndroidEventObserver {
            observer: Mutex::new(Some(observer)),
            comic_id: native_id,
            chapter_id,
            last_emitted: Mutex::new(Instant::now()),
        });
        let _observer_disconnect = AndroidObserverDisconnect(event_observer.clone());
        let fetcher = Arc::new(SourcePageFetcher { source });
        let deps = DownloadDeps {
            config: Arc::new(RwLock::new(config)),
            observer: event_observer.clone(),
            fetcher,
            chapter_sem: self.chapter_sem.clone(),
            img_sem: self.img_sem.clone(),
            byte_per_sec: self.byte_per_sec.clone(),
            img_client: self.img_client.clone(),
        };
        let task = DownloadTask::new(deps, comic, chapter_id).map_err(parse_error)?;
        let chapter_download_dir = task.chapter_info.chapter_download_dir.clone();
        let temp_download_dir = task.chapter_info.get_temp_download_dir();
        let (chapter_download_dir, temp_download_dir) =
            match (chapter_download_dir, temp_download_dir) {
                (Some(chapter_download_dir), Ok(temp_download_dir)) => {
                    (chapter_download_dir, temp_download_dir)
                }
                (None, _) => {
                    task.delete_sender.send_replace(());
                    return Err(parse_error("Chapter download directory is missing"));
                }
                (_, Err(error)) => {
                    task.delete_sender.send_replace(());
                    return Err(parse_error(error));
                }
            };
        registration.replace_sender(task.delete_sender.clone(), temp_download_dir.clone())?;
        let mut state_rx = task.state_sender.subscribe();
        let mut delete_rx = task.delete_sender.subscribe();

        let result = loop {
            match *state_rx.borrow() {
                DownloadTaskState::Completed => break Ok(()),
                DownloadTaskState::Failed => {
                    break Err(AndroidError::Network {
                        detail: format!("Failed to download chapter {chapter_id}"),
                    });
                }
                DownloadTaskState::Pending
                | DownloadTaskState::Downloading
                | DownloadTaskState::Paused => {}
            }

            tokio::select! {
                state_changed = state_rx.changed() => {
                    if state_changed.is_err() {
                        break Err(AndroidError::Network {
                            detail: format!("Failed to download chapter {chapter_id}"),
                        });
                    }
                }
                _ = delete_rx.changed() => {
                    break Err(AndroidError::Cancelled {
                        detail: format!("Cancelled chapter {chapter_id}"),
                    });
                }
            }
        };

        match result {
            Ok(()) => {
                let page_count = task.total_img_count.load(Ordering::Relaxed) as i32;
                registration.finish();
                Ok(DownloadedChapter {
                    comic_id: native_id,
                    comic_title: task.comic.title.clone(),
                    chapter_id,
                    chapter_title: task.chapter_info.chapter_title.clone(),
                    group_name: task.chapter_info.group_name.clone(),
                    order: task.chapter_info.order,
                    page_count,
                    chapter_dir: chapter_download_dir.to_string_lossy().into_owned(),
                })
            }
            Err(error) => {
                let _ = remove_directory(&temp_download_dir);
                registration.finish();
                Err(error)
            }
        }
    }
}
