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
    pub virtual_user: i64,
    pub runtime: String,
    pub compatibility: String,
    pub version_code: i64,
    pub abi: String,
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
    let mut value: serde_json::Value = serde_json::from_str(input).map_err(|_| "Invalid metadata schema")?;
    let schema = value.get("schema").and_then(|v| v.as_u64()).ok_or("Missing schema")?;
    if !(1..=3).contains(&schema) { return Err("Unsupported schema".into()); }
    if schema < 3 {
        let rows = value.get_mut("instances").and_then(|v| v.as_array_mut()).ok_or("Missing instances")?;
        for row in rows {
            let row = row.as_object_mut().ok_or("Invalid instance")?;
            if schema == 1 {
                row.remove("locked");
                row.insert("number".into(), 1.into()); row.insert("engine".into(), "work_profile".into());
            }
            if row.get("engine").and_then(|v| v.as_str()) != Some("work_profile") || row.get("number").and_then(|v| v.as_u64()) != Some(1) {
                return Err("Legacy metadata must describe work-profile copies".into());
            }
            // New fields are forbidden in older schemas instead of silently overriding them.
            for key in ["virtual_user", "runtime", "compatibility", "version_code", "abi"] {
                if row.contains_key(key) { return Err("Unexpected legacy field".into()); }
            }
            row.insert("virtual_user".into(), (-1).into()); row.insert("runtime".into(), "legacy".into());
            row.insert("compatibility".into(), "legacy".into()); row.insert("version_code".into(), 0.into()); row.insert("abi".into(), "unknown".into());
        }
        value["schema"] = 3.into();
    }
    let registry: Registry = serde_json::from_value(value).map_err(|_| "Invalid metadata schema")?;
    if registry.instances.len() > 512 { return Err("Instance limit".into()); }
    let mut ids = HashSet::new();
    let mut packages = HashSet::new();
    let mut virtual_users = HashSet::new();
    let mut numbers = HashSet::new();
    for row in &registry.instances {
        if row.id.len() != 36 || !row.id.bytes().enumerate().all(|(i,b)| {
            if [8,13,18,23].contains(&i) { b == b'-' } else { b.is_ascii_hexdigit() && (row.engine != "virtual" || !b.is_ascii_uppercase()) }
        }) { return Err("Invalid instance identifier".into()); }
        if !valid_package(&row.package) || row.name.trim().is_empty() || row.name.chars().count() > 80 || row.name.chars().any(char::is_control) {
            return Err("Invalid package or display name".into());
        }
        if row.profile < 0 || row.created < 0 || row.last_launch < 0 || row.accent > 4 || row.version_code < 0 || !(1..=512).contains(&row.number) {
            return Err("Invalid metadata value".into());
        }
        match row.engine.as_str() {
            "work_profile" => {
                if row.number != 1 || row.virtual_user != -1 || row.runtime != "legacy" || row.compatibility != "legacy" || row.abi != "unknown" || row.version_code != 0 {
                    return Err("Invalid legacy metadata".into());
                }
                if !packages.insert((&row.package, row.profile)) { return Err("Duplicate work-profile copy".into()); }
            }
            "virtual" => {
                if !(0..=1_000_000_000).contains(&row.virtual_user) || row.profile != row.virtual_user || row.compatibility != "probe_only"
                    || !["ready", "stopped", "failed", "missing"].contains(&row.runtime.as_str()) || row.abi != "bytecode" {
                    return Err("Invalid virtual metadata".into());
                }
                if !virtual_users.insert(row.virtual_user) || !numbers.insert((&row.package, row.number)) { return Err("Duplicate virtual user or clone number".into()); }
            }
            _ => return Err("Unsupported engine".into()),
        }
        if !ids.insert(&row.id) { return Err("Duplicate instance identifier".into()); }
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
        Instance { id: "00112233-4455-6677-8899-aabbccddeeff".into(), package: "org.example.probe".into(), name: "Second account".into(), profile: 10, created: 1, last_launch: 0, accent: 0, number: 1, engine: "work_profile".into(), virtual_user: -1, runtime: "legacy".into(), compatibility: "legacy".into(), version_code: 0, abi: "unknown".into() }
    }
    fn json(rows: Vec<Instance>) -> String { serde_json::to_string(&Registry { schema: 3, instances: rows }).unwrap() }
    #[test] fn roundtrip() { let s=json(vec![row()]); assert_eq!(canonicalize(&s).unwrap(),s); }
    #[test] fn duplicate_package_rejected() { let mut b=row(); b.id="11112233-4455-6677-8899-aabbccddeeff".into(); assert!(canonicalize(&json(vec![row(),b])).is_err()); }
    #[test] fn duplicate_id_rejected() { let mut b=row(); b.package="org.other.app".into(); assert!(canonicalize(&json(vec![row(),b])).is_err()); }
    #[test] fn traversal_rejected() { assert!(!valid_package("../../data")); assert!(!valid_package("org..app")); assert!(!valid_package("org.1app")); }
    #[test] fn empty_and_control_name_rejected() { let mut r=row(); r.name=" \n".into(); assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn oversized_rejected() { assert!(canonicalize(&"x".repeat(MAX_BYTES+1)).is_err()); }
    #[test] fn unknown_schema_rejected() { assert!(canonicalize("{\"schema\":4,\"instances\":[]}").is_err()); }
    #[test] fn unknown_field_rejected() { assert!(canonicalize("{\"schema\":2,\"instances\":[],\"secret\":true}").is_err()); }
    #[test] fn unsupported_engine_rejected() { let mut r=row();r.engine="virtual".into();assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn unsupported_copy_number_rejected() { let mut r=row();r.number=2;assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn invalid_profile_rejected() { let mut r=row(); r.profile=-1; assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn different_profiles_allowed() { let mut b=row(); b.profile=11; b.id="11112233-4455-6677-8899-aabbccddeeff".into(); assert!(canonicalize(&json(vec![row(),b])).is_ok()); }
    fn virtual_row(user: i64, number: u32) -> Instance {
        let mut r = row(); r.id = format!("{:08x}-4455-6677-8899-aabbccddeeff", user); r.engine = "virtual".into();
        r.profile = user; r.virtual_user = user; r.number = number; r.runtime = "ready".into(); r.compatibility = "probe_only".into(); r.abi = "bytecode".into(); r
    }
    #[test] fn multiple_virtual_copies_allowed() { assert!(canonicalize(&json(vec![virtual_row(0,1),virtual_row(1,2), row()])).is_ok()); }
    #[test] fn duplicate_virtual_user_rejected() { let mut r=virtual_row(0,2); r.id=row().id; assert!(canonicalize(&json(vec![virtual_row(0,1),r])).is_err()); }
    #[test] fn duplicate_clone_number_rejected() { assert!(canonicalize(&json(vec![virtual_row(0,1),virtual_row(1,1)])).is_err()); }
    #[test] fn invalid_virtual_identity_rejected() { let mut r=virtual_row(0,1); r.virtual_user=-1; assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn legacy_uppercase_identifiers_preserved() { let mut r=row(); r.id=r.id.to_ascii_uppercase(); let s=json(vec![r]); assert_eq!(canonicalize(&s).unwrap(),s); }
    #[test] fn virtual_identifiers_are_canonical_for_storage() { let mut r=virtual_row(0,1); r.id=r.id.to_ascii_uppercase(); assert!(canonicalize(&json(vec![r])).is_err()); }
    #[test] fn legacy_schemas_upgrade_without_data_loss() {
        for schema in [1,2] {
            let mut value=serde_json::to_value(row()).unwrap();
            let obj=value.as_object_mut().unwrap();
            for key in ["virtual_user","runtime","compatibility","version_code","abi"] { obj.remove(key); }
            if schema==1 { obj.remove("number"); obj.remove("engine"); obj.insert("locked".into(),true.into()); }
            let input=serde_json::json!({"schema":schema,"instances":[value]}).to_string();
            assert_eq!(canonicalize(&input).unwrap(),json(vec![row()]));
        }
    }
}
