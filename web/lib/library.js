// SPDX-License-Identifier: MIT
// The firmware library: The Firmware Hub's list as the index, and each package fetched from its author's
// GitHub, never from the hub. As src/library.rs on the desktop, without keeping anything: INSTALL fetches.
//
// A browser may read GitHub's API and the files in a repository (raw.githubusercontent.com), but not a
// release's files: GitHub sends them without the header that lets a page read them. Those come through
// this site's /api/asset, which fetches the same file from the author's release and passes it on. The
// SHA-256 in the list is checked here before anything is written, whichever way a package came.
import { parsePackage, sha256Hex } from "./formats.js";

export const ATTRIBUTION = "Firmware list: The Firmware Hub – https://thefirmwarehub.com (CC BY 4.0, unchanged)";
export const HUB = "https://thefirmwarehub.com/catalog.json";

const str = (v) => (typeof v === "string" && v ? v : null);

/** The list's FM-1 firmwares and M-VAVE's own package, as the desktop reads them (Catalog::parse). */
export function parseCatalog(v) {
  const list = Array.isArray(v?.firmwares) ? v.firmwares : null;
  if (!list) throw new Error('the firmware list has no "firmwares"');
  const entries = [];
  const st = v.stock;
  if (str(st?.pkg) && str(st?.sha256)) {
    const product = str(st.product) ?? "FM-1";
    const version = product.split("_").pop();
    entries.push({
      id: `mvave-${version.toLowerCase()}`, name: "M-VAVE FM-1 (official)", version, author: "M-VAVE",
      repo: null, tag: null, release: null, assetPkg: null, pkg: st.pkg, sha256: st.sha256.toLowerCase(),
      size: Number(st.size) || 0, chip: "any", license: "Apache-2.0", kinds: ["stock"], beta: false,
      elsewhere: false, stock: true, source: str(st.source), site: str(st.source),
    });
  }
  for (const f of list) {
    if (!str(f?.id) || !str(f.pkg) || !str(f.sha256)) continue;
    entries.push({
      id: f.id, name: str(f.name) ?? f.id, version: str(f.version) ?? "", author: str(f.author) ?? "",
      repo: str(f.repo), tag: str(f.tag), release: str(f.release), assetPkg: str(f.assetPkg),
      pkg: f.pkg, sha256: f.sha256.toLowerCase(), size: Number(f.size) || 0, chip: str(f.chip) ?? "any",
      license: str(f.lic) ?? "?", kinds: Array.isArray(f.kind) ? f.kind.filter(str) : str(f.kind) ? [f.kind] : [],
      beta: f.beta === true, elsewhere: f.linkOnly === true || f.externalInstall === true, stock: false,
      source: str(f.source), site: str(f.site) ?? str(f.release) ?? (str(f.repo) && `https://github.com/${f.repo}`),
    });
  }
  return { entries, updated: str(v.updated) ?? "" };
}

/** Every word typed, in any of the firmware's texts, ignoring case. */
export function matchesSearch(query, e) {
  const hay = [e.name, e.id, e.author, e.version, ...e.kinds].join(" ").toLowerCase();
  return query.toLowerCase().split(/\s+/).filter(Boolean).every((w) => hay.includes(w));
}

/** The FM-1's newer flash chip (UCUN UC25HQ80): a firmware marked "old" doesn't run on it. */
export const NEW_CHIP = 0xb36014;
export const fitsChip = (e, jedec) => !(e.chip === "old" && jedec === NEW_CHIP);

const enc = (s) => s.split("/").map(encodeURIComponent).join("/");

/**
 * Where the package may be, in the order to try (Library::resolve): the author's release, or with no
 * release the files in the author's repository; never the hub. Named as the list says first, then the
 * pattern the list gives, then FM-1 builds, then the other .fwsc: the SHA-256 decides which one it is.
 * `json` fetches GitHub's API. Each candidate is { name, url, relay }: relay when a page can't read it.
 */
export async function resolve(e, json) {
  if (e.stock) {
    const m = /^https:\/\/github\.com\/([^/]+\/[^/]+)\/blob\/(.+)$/.exec(e.source ?? "");
    if (!m) throw new Error("the list gives no GitHub source for M-VAVE's package");
    return [{ name: e.pkg.split("/").pop(), url: `https://raw.githubusercontent.com/${m[1]}/${m[2]}`, relay: false }];
  }
  if (e.elsewhere) throw new Error(`${e.name} installs with its author's own installer`);
  if (!e.repo) throw new Error(`${e.name} has no GitHub repository in the list`);
  let files;
  if (e.tag) {
    const rel = await json(`https://api.github.com/repos/${e.repo}/releases/tags/${encodeURIComponent(e.tag)}`);
    files = (rel.assets ?? []).filter((a) => a.name?.endsWith(".fwsc"))
      .map((a) => ({ name: a.name, url: a.browser_download_url, relay: true }));
  } else {
    // no release: the package as a file in the author's repository (zp12, Tonitor, CS-1…)
    const info = await json(`https://api.github.com/repos/${e.repo}`);
    const branch = info.default_branch || "main";
    const tree = await json(`https://api.github.com/repos/${e.repo}/git/trees/${encodeURIComponent(branch)}?recursive=1`);
    files = (tree.tree ?? []).filter((x) => x.type === "blob" && x.path?.endsWith(".fwsc"))
      .map((x) => ({ name: x.path.split("/").pop(), url: `https://raw.githubusercontent.com/${e.repo}/${enc(branch)}/${enc(x.path)}`, relay: false }));
  }
  if (!files.length) throw new Error(`${e.repo} ${e.tag ?? ""} has no .fwsc package`);
  let pattern = null;
  try { pattern = e.assetPkg ? new RegExp(`^(?:${e.assetPkg})$`) : null; } catch { pattern = null; }
  const hubName = e.pkg.split("/").pop();
  const fm1 = (n) => /fm-?1/i.test(n);
  const ranks = [(n) => n === hubName, (n) => pattern?.test(n) ?? false, fm1, () => true];
  const order = [];
  for (const rank of ranks) for (const f of files) if (rank(f.name) && !order.includes(f)) order.push(f);
  return order;
}

/** A download that couldn't be done here: the page offers the author's release page instead. */
export class FetchFailed extends Error {
  constructor(message, page) { super(message); this.page = page; }
}

async function bytes(url, init, onProgress, size) {
  const r = await fetch(url, init);
  if (!r.ok) throw new Error(`${r.status} ${r.statusText || ""}`.trim());
  if (!r.body || !onProgress) return new Uint8Array(await r.arrayBuffer());
  const total = Number(r.headers.get("content-length")) || size || 0;
  const reader = r.body.getReader();
  const parts = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
    got += value.length;
    onProgress(got, total);
  }
  const out = new Uint8Array(got);
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

const githubJson = async (url) => {
  const r = await fetch(url, { headers: { Accept: "application/vnd.github+json" } });
  if (r.status === 403 || r.status === 429) throw new Error("GitHub's limit for this connection was reached (60 requests an hour): try again later");
  if (!r.ok) throw new Error(`GitHub answered ${r.status} for ${url}`);
  return r.json();
};

/** Checks a package against the list: its SHA-256, and that it is a firmware package for the FM-1. */
export async function checkPackage(e, data) {
  const sha = await sha256Hex(data);
  if (sha !== e.sha256) return { ok: false, why: `its SHA-256 is ${sha.slice(0, 12)}…, the list says ${e.sha256.slice(0, 12)}…` };
  const pkg = parsePackage(data);
  if (!/^(FM-1|MiniFM)$/.test(pkg.model ?? "")) return { ok: false, why: `it is a package for the ${pkg.model}, not the FM-1` };
  return { ok: true, pkg };
}

/**
 * Fetches the package from the author's GitHub and returns it once its SHA-256 matches the list: the
 * candidates in order, at most 6. A release file comes through `relay` (this site's /api/asset).
 */
export async function fetchPackage(e, { relay = "/api/asset", onProgress, log = () => {} } = {}) {
  const page = e.release ?? e.site ?? (e.repo ? `https://github.com/${e.repo}` : null);
  let candidates;
  try {
    candidates = await resolve(e, githubJson);
  } catch (cause) {
    throw new FetchFailed(`couldn't find ${e.name}'s package on GitHub: ${cause.message}`, page);
  }
  const tried = [];
  for (const c of candidates.slice(0, 6)) {
    const url = c.relay ? `${relay}?id=${encodeURIComponent(e.id)}&url=${encodeURIComponent(c.url)}` : c.url;
    log(`Fetching ${c.url}${c.relay ? " (through this site)" : ""}`);
    try {
      const data = await bytes(url, {}, onProgress, e.size);
      const check = await checkPackage(e, data);
      if (check.ok) return { data, pkg: check.pkg, from: c.url };
      tried.push(`${c.name}: ${check.why}`);
    } catch (cause) {
      tried.push(`${c.name}: ${cause.message}`);
    }
  }
  throw new FetchFailed(`no file of ${e.name} ${e.version} matched the list (${tried.join("; ")})`, page);
}
