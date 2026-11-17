// SPDX-License-Identifier: MIT
// The page with its functions, on this computer: node web/dev.mjs [port], then http://localhost:8000.
// Browsers allow Web MIDI on localhost. On Vercel the same files in api/ are the functions.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL(".", import.meta.url));
const port = Number(process.argv[2]) || 8000;
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".json": "application/json",
  ".png": "image/png", ".ttf": "font/ttf", ".fwsc": "application/octet-stream", ".txt": "text/plain" };
const cache = new Map(); // /api/catalog, kept a day here as Vercel keeps it

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${port}`);
  try {
    if (url.pathname.startsWith("/api/") && !url.pathname.slice(5).startsWith("_")) {
      const name = url.pathname.slice(5).replace(/\.js$/, "");
      if (!/^[a-z]+$/.test(name)) throw Object.assign(new Error("no such function"), { status: 404 });
      const hit = name === "catalog" && cache.get(url.pathname);
      let r = hit && Date.now() - hit.at < 86400e3 ? hit.r.clone() : null;
      if (!r) {
        const mod = await import(join(root, "api", `${name}.js`));
        r = await mod.GET(new Request(url, { headers: req.headers }));
        if (name === "catalog" && r.ok) cache.set(url.pathname, { at: Date.now(), r: r.clone() });
      }
      res.writeHead(r.status, Object.fromEntries(r.headers));
      res.end(Buffer.from(await r.arrayBuffer()));
      return;
    }
    const path = normalize(join(root, url.pathname === "/" ? "index.html" : decodeURIComponent(url.pathname)));
    if (!path.startsWith(root) || path.includes("/test/")) throw Object.assign(new Error("not here"), { status: 404 });
    const body = await readFile(path);
    res.writeHead(200, { "content-type": TYPES[extname(path)] ?? "application/octet-stream", "cache-control": "no-cache" });
    res.end(body);
  } catch (cause) {
    res.writeHead(cause.status ?? (cause.code === "ENOENT" ? 404 : 500), { "content-type": "text/plain" });
    res.end(String(cause.message));
  }
}).listen(port, "127.0.0.1", () => console.log(`M-Flasher + for the web: http://localhost:${port}`));
