// SPDX-License-Identifier: MIT
// GET /api/catalog: The Firmware Hub's list, the index of the library. Read from the hub with this
// site's User-Agent, and kept by Vercel's cache for a day, so the hub is read at most about once a day
// for every visitor together, as its rules ask, rather than once by each browser.
import { fetchHub, json } from "./_lib.js";

export async function GET() {
  try {
    const { text } = await fetchHub();
    return new Response(text, {
      headers: {
        "content-type": "application/json",
        "cache-control": "public, max-age=3600, s-maxage=86400, stale-while-revalidate=3600",
      },
    });
  } catch (cause) {
    return json(502, { error: `couldn't read The Firmware Hub's list: ${cause.message}` });
  }
}
