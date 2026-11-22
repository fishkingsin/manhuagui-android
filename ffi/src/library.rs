use std::sync::Arc;

use library::Library;
use source_api::{CoreConfig, ProxyMode, Source, UpdateWindow};
use source_manhuagui::{ManhuaguiClient, ManhuaguiSource};

use crate::types::{
    AndroidError, AndroidUpdateWindow, BrowsePage, ChapterPagesResult, ComicDetails, ComicListItem,
    SearchPage,
};

#[derive(uniffi::Object)]
pub struct AndroidLibrary {
    inner: Library,
}

impl AndroidLibrary {
    pub fn with_library(library: Library) -> Self {
        Self { inner: library }
    }

    fn source(&self) -> Result<Arc<dyn Source>, AndroidError> {
        self.inner
            .source("manhuagui")
            .ok_or_else(|| AndroidError::NotFound {
                detail: "Manhuagui source is not registered".to_owned(),
            })
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
        Self { inner }
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
}
