// SPDX-License-Identifier: MIT
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { NEW_CHIP, fitsChip, matchesSearch, parseCatalog, resolve } from "../lib/library.js";
import { USER_AGENT, releaseFile } from "../api/_lib.js";
import { handle } from "../api/asset.js";

const LIST = {
  updated: "2026-11-17",
  stock: { product: "FM-1_015", pkg: "fw/mvave-015.fwsc", sha256: "AA", source: "https://github.com/m-vave/fm1_open/blob/main/fw/FM-1_015.fwsc" },
  firmwares: [
    { id: "felucca", name: "Felucca", version: "1.8.2", author: "Leo Kuroshita", repo: "hugelton/Felucca", tag: "v1.8.2",
      release: "https://github.com/hugelton/Felucca/releases/tag/v1.8.2", pkg: "fw/felucca-1.8.2.fwsc", sha256: "BB", lic: "GPL-3.0", kind: ["groove"], chip: "new" },
    { id: "zp12", name: "zp12", version: "2.5", repo: "zvenson/zp12", tag: null, pkg: "fw/zp12-2.5.fwsc", sha256: "CC", chip: "old" },
    { id: "baudgirl", name: "Baud Girl", pkg: "fw/bg.fwsc", sha256: "DD", linkOnly: true, site: "https://baudgirl.com" },
    { id: "broken" },
  ],
};

test("the list reads as the desktop reads it: M-VAVE's package first, entries without a package left out", () => {
  const { entries, updated } = parseCatalog(LIST);
  assert.equal(updated, "2026-11-17");
  assert.deepEqual(entries.map((e) => e.id), ["mvave-015", "felucca", "zp12", "baudgirl"]);
  assert.equal(entries[0].stock, true);
  assert.equal(entries[1].sha256, "bb");
  assert.deepEqual(entries[1].kinds, ["groove"]);
  assert.equal(entries[3].elsewhere, true);
  assert.throws(() => parseCatalog({}), /firmwares/);
});

test("search, and the newer flash chip hides what's made for the older one", () => {
  const [, felucca, zp12] = parseCatalog(LIST).entries;
  assert.ok(matchesSearch("leo GROOVE", felucca));
  assert.ok(!matchesSearch("drums", felucca));
  assert.ok(fitsChip(zp12, 0x856014));
  assert.ok(!fitsChip(zp12, NEW_CHIP));
  assert.ok(fitsChip(felucca, NEW_CHIP));
});

test("a release's packages come through the site, named as the list says first; never from the hub", async () => {
  const felucca = parseCatalog(LIST).entries[1];
  const asked = [];
  const json = async (url) => {
    asked.push(url);
    return { assets: [
      { name: "felucca-smk37.fwsc", browser_download_url: "https://github.com/hugelton/Felucca/releases/download/v1.8.2/felucca-smk37.fwsc" },
      { name: "notes.txt", browser_download_url: "https://github.com/hugelton/Felucca/releases/download/v1.8.2/notes.txt" },
      { name: "felucca-1.8.2.fwsc", browser_download_url: "https://github.com/hugelton/Felucca/releases/download/v1.8.2/felucca-1.8.2.fwsc" },
    ] };
  };
  const order = await resolve(felucca, json);
  assert.deepEqual(asked, ["https://api.github.com/repos/hugelton/Felucca/releases/tags/v1.8.2"]);
  assert.deepEqual(order.map((c) => c.name), ["felucca-1.8.2.fwsc", "felucca-smk37.fwsc"]);
  assert.ok(order.every((c) => c.relay && c.url.startsWith("https://github.com/hugelton/Felucca/releases/download/")));
  assert.ok(order.every((c) => !c.url.includes("thefirmwarehub")));
});

test("with no release, the package is a file in the author's repository, fetched directly", async () => {
  const zp12 = parseCatalog(LIST).entries[2];
  const json = async (url) => url.endsWith("/zp12") ? { default_branch: "main" }
    : { tree: [{ type: "blob", path: "build/fm1/zp12-2.5.fwsc" }, { type: "blob", path: "README.md" }] };
  const order = await resolve(zp12, json);
  assert.deepEqual(order, [{ name: "zp12-2.5.fwsc", url: "https://raw.githubusercontent.com/zvenson/zp12/main/build/fm1/zp12-2.5.fwsc", relay: false }]);
});

test("M-VAVE's own package is its file on GitHub; a firmware with its own installer has none", async () => {
  const [stock, , , baudgirl] = parseCatalog(LIST).entries;
  assert.deepEqual(await resolve(stock, null), [{ name: "mvave-015.fwsc", url: "https://raw.githubusercontent.com/m-vave/fm1_open/main/fw/FM-1_015.fwsc", relay: false }]);
  await assert.rejects(resolve(baudgirl, null), /own installer/);
});

test("the site's User-Agent names M-Flasher + alone", () => {
  assert.match(USER_AGENT, /^m-flasher-plus-web\/\d+\.\d+\.\d+ \(\+https:\/\/github\.com\/wellingtonsaam\/m-flasher-plus\)$/);
  for (const not of ["jvitkauskas", "m-flasher/", "fork"]) assert.ok(!USER_AGENT.includes(not));
});

test("the relay takes only a .fwsc of the release the list names", () => {
  const e = { repo: "hugelton/Felucca", tag: "v1.8.2" };
  const base = "https://github.com/hugelton/Felucca/releases/download/v1.8.2/";
  assert.deepEqual(releaseFile(e, `${base}felucca-1.8.2.fwsc`), { name: "felucca-1.8.2.fwsc" });
  assert.ok(releaseFile(e, "https://github.com/HUGELTON/felucca/releases/download/v1.8.2/x.fwsc"));
  for (const bad of [
    `${base}felucca.bin`,
    "https://github.com/someone/else/releases/download/v1.8.2/x.fwsc",
    "https://github.com/hugelton/Felucca/releases/download/v1.8.1/x.fwsc",
    "https://evil.example/hugelton/Felucca/releases/download/v1.8.2/x.fwsc",
    "http://github.com/hugelton/Felucca/releases/download/v1.8.2/x.fwsc",
    `${base}x.fwsc?y=1`,
    `${base}..%2F..%2Fx.fwsc`,
    "https://github.com/hugelton/Felucca/releases/download/v1.8.2/a/b.fwsc",
    "not a url",
  ]) assert.equal(releaseFile(e, bad), null, bad);
  assert.equal(releaseFile({ repo: "zvenson/zp12", tag: null }, "https://github.com/zvenson/zp12/releases/download/x/y.fwsc"), null);
});

test("the relay passes the author's file on only when its SHA-256 is the list's", async () => {
  const pkg = new Uint8Array([1, 2, 3, 4]);
  const sha = createHash("sha256").update(pkg).digest("hex");
  const list = { firmwares: [{ id: "felucca", repo: "hugelton/Felucca", tag: "v1.8.2", sha256: sha }] };
  const file = "https://github.com/hugelton/Felucca/releases/download/v1.8.2/felucca-1.8.2.fwsc";
  const seen = [];
  const fetcher = async (url, init) => {
    seen.push([String(url), init?.headers?.["user-agent"]]);
    if (String(url).endsWith("/api/catalog")) return Response.json(list);
    if (String(url) === file) return new Response(pkg);
    return new Response("no", { status: 404 });
  };
  const ask = (id, url) => handle(new Request(`https://site.example/api/asset?id=${id}&url=${encodeURIComponent(url)}`), fetcher);

  const ok = await ask("felucca", file);
  assert.equal(ok.status, 200);
  assert.deepEqual(new Uint8Array(await ok.arrayBuffer()), pkg);
  assert.equal(ok.headers.get("x-sha256"), sha);
  assert.deepEqual(seen.at(-1), [file, USER_AGENT]);

  assert.equal((await ask("felucca", "https://example.com/x.fwsc")).status, 403);
  assert.equal((await ask("nothing", file)).status, 404);
  assert.equal((await handle(new Request("https://site.example/api/asset"), fetcher)).status, 400);

  list.firmwares[0].sha256 = "0".repeat(64);
  assert.equal((await ask("felucca", file)).status, 409);
});
