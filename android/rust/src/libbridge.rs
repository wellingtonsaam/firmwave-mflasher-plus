//! The JNI side of the library (Figma A02): the desktop's src/library.rs as
//! is. The firmware list is read from the hub at most once a day; firmwares
//! come from their authors' releases and are kept only when their SHA-256
//! matches the list. Nothing here touches the unit.

use crate::library::{Http, Library, Synced};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use std::path::PathBuf;

fn clean(t: &str) -> String {
    t.replace(['|', '\n'], " ")
}

/// "NOTE|what happened" and one `FW|id|name|version|byline|kinds|chip|beta|saved|elsewhere|older|favorite`
/// line per firmware, M-VAVE's own first.
fn sync(dir: &str) -> Result<String, String> {
    let lib = Library::new(PathBuf::from(dir));
    let mut note = match lib.sync(&Http::new(), chrono::Utc::now()) {
        Ok(Synced::Fetched) => "List fetched now".to_string(),
        Ok(Synced::Recent(at)) => format!("List fetched {at}"),
        Err(e) => format!("Offline, the list on this phone: {e}"),
    };
    let favorites = lib.favorites();
    let mut out = vec![];
    match lib.catalog()? {
        Some(cat) => {
            lib.index_saved(&cat);
            note = format!("{note} · {} firmwares, list updated {}", cat.entries.len(), cat.updated);
            let mut entries: Vec<_> = cat.entries.iter().collect();
            entries.sort_by_key(|e| !e.stock);
            for e in entries {
                let b = |x: bool| if x { "1" } else { "0" };
                out.push(format!(
                    "FW|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
                    clean(&e.id), clean(&e.name), clean(&e.version),
                    clean(&format!("{} · {}", if e.author.is_empty() { "?" } else { &e.author }, e.license)),
                    clean(&e.kinds.join(" ")), clean(&e.chip), b(e.beta), b(lib.downloaded(e)), b(e.elsewhere),
                    clean(&lib.older_versions(e).join(",")), b(favorites.contains(&e.id)),
                ));
            }
        }
        None => note = format!("{note} · no list on this phone yet"),
    }
    out.insert(0, format!("NOTE|{}", clean(&note)));
    Ok(out.join("\n"))
}

fn get(dir: &str, id: &str) -> Result<String, String> {
    let lib = Library::new(PathBuf::from(dir));
    let cat = lib.catalog()?.ok_or("no firmware list on this phone yet")?;
    let e = cat.find(id).ok_or_else(|| format!("{id} is not in the firmware list"))?;
    let (path, _) = lib.get(e, &Http::new())?;
    lib.index_saved(&cat);
    Ok(path.display().to_string())
}

fn favorite(dir: &str, id: &str, on: bool) -> Result<String, String> {
    Library::new(PathBuf::from(dir)).set_favorite(id, on).map(|_| String::new())
}

fn delete(dir: &str, id: &str) -> Result<String, String> {
    let gone = Library::new(PathBuf::from(dir)).delete(id)?;
    Ok(gone.iter().map(|p| p.display().to_string()).collect::<Vec<_>>().join("\n"))
}

fn reply(env: &mut JNIEnv, r: std::thread::Result<Result<String, String>>) -> jstring {
    let text = match r {
        Ok(Ok(s)) => format!("OK\n{s}"),
        Ok(Err(e)) => format!("ERROR\n{e}"),
        Err(_) => "ERROR\ninternal error (panic)".into(),
    };
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_librarySync<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>, dir: JString<'l>,
) -> jstring {
    let dir = string(&mut env, &dir);
    let r = std::panic::catch_unwind(|| sync(&dir));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_libraryGet<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>, dir: JString<'l>, id: JString<'l>,
) -> jstring {
    let (dir, id) = (string(&mut env, &dir), string(&mut env, &id));
    let r = std::panic::catch_unwind(|| get(&dir, &id));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_libraryFavorite<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>, dir: JString<'l>, id: JString<'l>, on: jboolean,
) -> jstring {
    let (dir, id) = (string(&mut env, &dir), string(&mut env, &id));
    let r = std::panic::catch_unwind(|| favorite(&dir, &id, on != 0));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_wellingtonsaam_mflasherplus_Native_libraryDelete<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>, dir: JString<'l>, id: JString<'l>,
) -> jstring {
    let (dir, id) = (string(&mut env, &dir), string(&mut env, &id));
    let r = std::panic::catch_unwind(|| delete(&dir, &id));
    reply(&mut env, r)
}
