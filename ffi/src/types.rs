use std::{collections::HashMap, error::Error, fmt};

use source_api::{
    ChapterInfo, ChapterPages, Comic, ComicInSearch, ComicSummary, PageRef, SourceError,
    UpdateWindow,
};

#[uniffi::export(callback_interface)]
pub trait AndroidDownloadObserver: Send + Sync {
    fn on_progress(&self, comic_id: i64, chapter_id: i64, downloaded_pages: u32, total_pages: u32);
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DownloadedChapter {
    pub comic_id: i64,
    pub comic_title: String,
    pub chapter_id: i64,
    pub chapter_title: String,
    pub group_name: String,
    pub order: f64,
    pub page_count: i32,
    pub chapter_dir: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LocalChapterNavigation {
    pub prev_chapter_id: Option<i64>,
    pub next_chapter_id: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidExportFormat {
    Cbz,
    Pdf,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExportedChapterFile {
    pub chapter_id: i64,
    pub file_name: String,
    pub file_path: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidUpdateWindow {
    Days7,
    Days15,
    Days30,
    All,
}

impl From<AndroidUpdateWindow> for UpdateWindow {
    fn from(window: AndroidUpdateWindow) -> Self {
        match window {
            AndroidUpdateWindow::Days7 => Self::Days7,
            AndroidUpdateWindow::Days15 => Self::Days15,
            AndroidUpdateWindow::Days30 => Self::Days30,
            AndroidUpdateWindow::All => Self::All,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum AndroidError {
    Network { detail: String },
    RateLimited { detail: String },
    ParseFailed { detail: String },
    NotFound { detail: String },
    Cancelled { detail: String },
}

impl From<SourceError> for AndroidError {
    fn from(error: SourceError) -> Self {
        match error {
            SourceError::Network { message } => Self::Network { detail: message },
            SourceError::RateLimited { message } => Self::RateLimited { detail: message },
            SourceError::ParseFailed { message } => Self::ParseFailed { detail: message },
            SourceError::NotFound { message } => Self::NotFound { detail: message },
            SourceError::Cancelled { message } => Self::Cancelled { detail: message },
        }
    }
}

impl fmt::Display for AndroidError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let detail = match self {
            Self::Network { detail }
            | Self::RateLimited { detail }
            | Self::ParseFailed { detail }
            | Self::NotFound { detail }
            | Self::Cancelled { detail } => detail,
        };
        formatter.write_str(detail)
    }
}

impl Error for AndroidError {}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ComicListItem {
    pub comic_id: i64,
    pub title: String,
    pub cover_url: String,
    pub status_text: String,
    pub update_text: Option<String>,
    pub rating: Option<f32>,
}

impl From<ComicSummary> for ComicListItem {
    fn from(summary: ComicSummary) -> Self {
        Self {
            comic_id: summary.comic_id.native_id,
            title: summary.title,
            cover_url: summary.cover_url,
            status_text: summary.status_line,
            update_text: summary.updated_on.map(|date| date.to_string()),
            rating: summary.rating,
        }
    }
}

impl From<ComicInSearch> for ComicListItem {
    fn from(comic: ComicInSearch) -> Self {
        Self {
            comic_id: comic.id,
            title: comic.title,
            cover_url: comic.cover,
            status_text: comic.status,
            update_text: (!comic.update_time.is_empty()).then_some(comic.update_time),
            rating: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BrowsePage {
    pub items: Vec<ComicListItem>,
    pub page: i64,
    pub total_pages: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SearchPage {
    pub items: Vec<ComicListItem>,
    pub current: i64,
    pub total: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ChapterItem {
    pub chapter_id: i64,
    pub chapter_title: String,
    pub chapter_size: i64,
    pub group_name: String,
    pub group_size: i64,
    pub order: f64,
}

impl From<ChapterInfo> for ChapterItem {
    fn from(chapter: ChapterInfo) -> Self {
        Self {
            chapter_id: chapter.chapter_id,
            chapter_title: chapter.chapter_title,
            chapter_size: chapter.chapter_size,
            group_name: chapter.group_name,
            group_size: chapter.group_size,
            order: chapter.order,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ComicDetails {
    pub comic_id: i64,
    pub title: String,
    pub subtitle: Option<String>,
    pub cover_url: String,
    pub status_text: String,
    pub update_text: String,
    pub year: i64,
    pub region: String,
    pub genres: Vec<String>,
    pub authors: Vec<String>,
    pub aliases: Vec<String>,
    pub intro: String,
    pub chapters: Vec<ChapterItem>,
}

impl From<Comic> for ComicDetails {
    fn from(comic: Comic) -> Self {
        Self {
            comic_id: comic.id,
            title: comic.title,
            subtitle: comic.subtitle,
            cover_url: comic.cover,
            status_text: comic.status,
            update_text: comic.update_time,
            year: comic.year,
            region: comic.region,
            genres: comic.genres,
            authors: comic.authors,
            aliases: comic.aliases,
            intro: comic.intro,
            chapters: comic
                .groups
                .into_values()
                .flatten()
                .map(Into::into)
                .collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ImagePage {
    pub url: String,
    pub headers: HashMap<String, String>,
}

impl From<PageRef> for ImagePage {
    fn from(page: PageRef) -> Self {
        Self {
            url: page.url,
            headers: page.headers,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ChapterPagesResult {
    pub pages: Vec<ImagePage>,
    pub prev_chapter_id: Option<i64>,
    pub next_chapter_id: Option<i64>,
}

impl From<ChapterPages> for ChapterPagesResult {
    fn from(chapter: ChapterPages) -> Self {
        Self {
            pages: chapter.pages.into_iter().map(Into::into).collect(),
            prev_chapter_id: chapter.prev_chapter_id,
            next_chapter_id: chapter.next_chapter_id,
        }
    }
}
