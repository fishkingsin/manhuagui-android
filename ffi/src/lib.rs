uniffi::setup_scaffolding!("manhuagui_ffi");

#[uniffi::export]
pub fn core_version() -> String {
    "ffi-stub".to_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn core_version_is_stub() {
        assert_eq!(super::core_version(), "ffi-stub");
    }
}
