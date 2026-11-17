// SPDX-License-Identifier: MIT
// GET /api/asset?id=<firmware>&url=<release file>: a firmware package from its author's GitHub release,
// passed on unchanged. A browser can't read a release file itself (GitHub sends it without the CORS
// header); this fetches the same file from the same place. Only a .fwsc of the release the list names
// for that firmware, and only if its SHA-256 is the one the list gives: nothing else goes through.
import { MAX_PACKAGE, USER_AGENT, fetchHub, json, releaseFile, sha256 } from "./_lib.js";

/** The list, from this site's own cached /api/catalog when it answers, else from the hub. */
async function catalog(request, fetcher) {
  try {
    const r = await fetcher(new URL("/api/catalog", request.url), { headers: { accept: "application/json" } });
    if (r.ok) {
      const v = await r.json();
      if (Array.isArray(v.firmwares)) return v;
    }
  } catch { /* the hub, then */ }
  return (await fetchHub(fetcher)).v;
}

export async function handle(request, fetcher = fetch) {
  const q = new URL(request.url).searchParams;
  const id = q.get("id");
  const url = q.get("url");
  if (!id || !url) return json(400, { error: "id and url are needed" });
  let list;
  try {
    list = await catalog(request, fetcher);
  } catch (cause) {
    return json(502, { error: `couldn't read the firmware list: ${cause.message}` });
  }
  const f = list.firmwares.find((x) => x?.id === id);
  if (!f) return json(404, { error: `${id} is not in the list` });
  const entry = { repo: f.repo, tag: f.tag };
  if (!releaseFile(entry, url)) return json(403, { error: "only a .fwsc of the release the list names for this firmware" });
  let r;
  try {
    r = await fetcher(url, { headers: { "user-agent": USER_AGENT, accept: "application/octet-stream" }, redirect: "follow" });
  } catch (cause) {
    return json(502, { error: `GitHub: ${cause.message}` });
  }
  if (!r.ok) return json(502, { error: `GitHub answered ${r.status}` });
  if (Number(r.headers.get("content-length")) > MAX_PACKAGE) return json(413, { error: "too large for a firmware package" });
  const data = new Uint8Array(await r.arrayBuffer());
  if (data.length > MAX_PACKAGE) return json(413, { error: "too large for a firmware package" });
  const sha = sha256(data);
  if (sha !== String(f.sha256).toLowerCase()) return json(409, { error: "this file is not the package the list names (SHA-256)", sha256: sha });
  return new Response(data, {
    headers: {
      "content-type": "application/octet-stream",
      "content-length": String(data.length),
      "x-sha256": sha,
      // the same bytes for as long as the list names them: kept a day, never in the browser for long
      "cache-control": "public, max-age=300, s-maxage=86400",
    },
  });
}

export const GET = (request) => handle(request);
