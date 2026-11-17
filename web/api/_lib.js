// SPDX-License-Identifier: MIT
// What the site's two functions share. Files starting with "_" are not routes on Vercel.
import { createHash } from "node:crypto";

/** Who is asking, as The Firmware Hub's rules want: M-Flasher + by its own name and address, nothing else. */
export const USER_AGENT = "m-flasher-plus-web/1.0.2 (+https://github.com/wellingtonsaam/m-flasher-plus)";
export const HUB = "https://thefirmwarehub.com/catalog.json";
/** The largest package passed on (the FM-1's flash is 1 MiB; packages are smaller). */
export const MAX_PACKAGE = 4 << 20;

export const json = (status, body, headers = {}) => new Response(JSON.stringify(body), {
  status, headers: { "content-type": "application/json", "cache-control": "no-store", ...headers },
});

export const sha256 = (data) => createHash("sha256").update(data).digest("hex");

/** The hub's list, read with this site's User-Agent. */
export async function fetchHub(fetcher = fetch) {
  const r = await fetcher(HUB, { headers: { "user-agent": USER_AGENT, accept: "application/json" } });
  if (!r.ok) throw new Error(`The Firmware Hub answered ${r.status}`);
  const text = await r.text();
  const v = JSON.parse(text);
  if (!Array.isArray(v.firmwares)) throw new Error('the list has no "firmwares"');
  return { text, v };
}

/**
 * Whether `url` is a file of this firmware's release in its author's repository, as the list names
 * them: https://github.com/<repo>/releases/download/<tag>/<name>.fwsc. Anything else is refused, so
 * the function can't fetch for anyone what the list doesn't name.
 */
export function releaseFile(entry, url) {
  if (!entry?.repo || !entry.tag) return null;
  let u;
  try { u = new URL(url); } catch { return null; }
  if (u.protocol !== "https:" || u.hostname !== "github.com" || u.search || u.hash || u.username || u.port) return null;
  const parts = u.pathname.split("/");
  if (parts.length !== 7 || parts[0] !== "" || parts[3] !== "releases" || parts[4] !== "download") return null;
  let tag, name;
  try { tag = decodeURIComponent(parts[5]); name = decodeURIComponent(parts[6]); } catch { return null; }
  if (`${parts[1]}/${parts[2]}`.toLowerCase() !== entry.repo.toLowerCase() || tag !== entry.tag) return null;
  if (!name.endsWith(".fwsc") || name.includes("/") || name.includes("\\")) return null;
  return { name };
}
