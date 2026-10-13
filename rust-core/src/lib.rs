//! Bounded, strict manager metadata validation. No third-party application data is read.
use jni::{objects::{JClass, JString}, sys::jstring, JNIEnv};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

pub const MAX_BYTES: usize = 1_048_576;
#[derive(Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Registry {
    pub schema: u32,
    pub instances: Vec<Instance>,
}
#[derive(Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Instance {
    pub id: String,
    pub package: String,
    pub name: String,
    pub profile: i64,
    pub created: i64,
    pub last_launch: i64,
    pub accent: u32,
    pub number: u32,
    pub engine: String,
}
pub fn valid_package(value: &str) -> bool {
    value.len() <= 255 && value.contains('.') && value.split('.').all(|part| {
        let mut bytes = part.bytes();
        matches!(bytes.next(), Some(b'a'..=b'z' | b'A'..=b'Z' | b'_')) &&
        bytes.all(|b| b.is_ascii_alphanumeric() || b == b'_')
    })
}
pub fn canonicalize(input: &str) -> Result<String, String> {
    if input.len() > MAX_BYTES { return Err("Metadata exceeds 1 MiB".into()); }
    let registry: Registry = serde_json::from_str(input).map_err(|_| "Invalid metadata schema")?;
    if registry.schema != 2 || registry.instances.len() > 512 { return Err("Unsupported schema or instance limit".into()); }
    let mut ids = HashSet::new();
    let mut packages = HashSet::new();
    for row in &registry.instances {
        if row.id.len() != 36 || !row.id.bytes().enumerate().all(|(i,b)| {
            if [8,13,18,23].contains(&i) { b == b'-' } else { b.is_ascii_hexdigit() }
        }) { return Err("Invalid instance identifier".into()); }
        if !valid_package(&row.package) || row.name.trim().is_empty() || row.name.chars().count() > 80 || row.name.chars().any(char::is_control) {
            return Err("Invalid package or display name".into());
        }
        if row.profile < 0 || row.created < 0 || row.last_launch < 0 || row.accent > 4 || row.number != 1 || row.engine != "work_profile" {
            return Err("Invalid metadata value".into());
        }
        if !ids.insert(&row.id) || !packages.insert((&row.package, row.profile)) {
            return Err("Duplicate instance; one copy per package per work profile".into());
        }
    }
    serde_json::to_string(&registry).map_err(|_| "Serialization failed".into())
}

// The JNI ABI requires an exported unmangled symbol. All argument handling uses
// the checked jni crate. No raw pointer dereference or handwritten unsafe block.
#[no_mangle]
pub extern "system" fn Java_org_parallelnest_app_core_NativeCore_canonicalize(
    mut env: JNIEnv, _class: JClass, input: JString,
) -> jstring {
    let result = env.get_string(&input).map_err(|_| "Invalid JNI string".to_string())
        .and_then(|s| canonicalize(&String::from(s)));
    match result {
        Ok(value) => env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn row() -> Instance {
        Instance { id: "00112233-4455-6677-8899-aabbccddeeff".into(), package: "org.example.probe".into(), name: "Second account".into(), profile: 10, created: 1, last_launch: 0, accent: 0, number: 1, engine: "work_profile".into() }
    }
    fn json(rows: Vec<Instance>) -> String { serde_json::to_string(&Registry { schema: 2, instances: rows }).unwrap() }
    #[test] fn roundtrip() { let s=json(vec![row()]); assert_eq!(canonicalize(&s).unwrap(),s); }
    #[test] fn duplicate_package_rejected() { let mut b=row(); b.id="11112233-4455-6677-8899-aabbccddeeff".into(); assert!(canonicalize(&json(vec![row(),b])).is_err()); }
    #[test] fn duplicate_id_rejected() { let mut b=row(); b.package="org.other.app".into(); assert!(canonicalize(&json(vec![row(),b])).is_err()); }
    #[test] fn traversal_rejected() { assert!(!valid_package("../../data")); assert!(!valid_package("org..app")); assert!(!valid_package("org.1app")); }
    #[test] fn empty_and_control_name_rejected() { let mut r=row(); r.name=" \n".into(); assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn oversized_rejected() { assert!(canonicalize(&"x".repeat(MAX_BYTES+1)).is_err()); }
    #[test] fn unknown_schema_rejected() { assert!(canonicalize("{\"schema\":3,\"instances\":[]}").is_err()); }
    #[test] fn unknown_field_rejected() { assert!(canonicalize("{\"schema\":2,\"instances\":[],\"secret\":true}").is_err()); }
    #[test] fn unsupported_engine_rejected() { let mut r=row();r.engine="virtual".into();assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn unsupported_copy_number_rejected() { let mut r=row();r.number=2;assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn invalid_profile_rejected() { let mut r=row(); r.profile=-1; assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn different_profiles_allowed() { let mut b=row(); b.profile=11; b.id="11112233-4455-6677-8899-aabbccddeeff".into(); assert!(canonicalize(&json(vec![row(),b])).is_ok()); }
}
