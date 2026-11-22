mod library;
mod types;

pub use library::AndroidLibrary;
pub use types::{
    AndroidError, AndroidUpdateWindow, BrowsePage, ChapterItem, ChapterPagesResult, ComicDetails,
    ComicListItem, ImagePage, SearchPage,
};

uniffi::setup_scaffolding!("manhuagui_ffi");

#[uniffi::export]
pub fn core_version() -> String {
    "v0.1.1-core".to_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn core_version_is_tagged_core() {
        assert_eq!(super::core_version(), "v0.1.1-core");
    }
}
