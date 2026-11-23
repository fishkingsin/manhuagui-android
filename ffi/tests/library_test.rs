use async_trait::async_trait;
use chrono::NaiveDate;
use library::Library;
use manhuagui_ffi::{
    AndroidError, AndroidLibrary, AndroidUpdateWindow, BrowsePage, ChapterItem, ChapterPagesResult,
    ComicDetails, ComicListItem, ImagePage, SearchPage,
};
use source_api::{
    BrowseResult, ChapterInfo, ChapterPages, Comic, ComicId, ComicInSearch, ComicSummary, PageRef,
    SearchResult, Source, SourceError, UpdateWindow,
};
use std::{collections::HashMap, path::PathBuf, sync::Arc};

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
