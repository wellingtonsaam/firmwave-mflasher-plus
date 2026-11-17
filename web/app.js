// SPDX-License-Identifier: MIT
// The page: finds a unit, says what it is, and offers what can be done with it.
import { BOOT_AREA, appFilesStatus, crc32, hex, packageImage, sha256Hex } from "./lib/formats.js";
import { FINISH, Link, Unit, UnitLost, findPorts } from "./lib/protocol.js";
import { analyse, describePlan, flashTested, flashText, planFirmware, planRestore } from "./lib/unit.js";
import { loadManifest, loadPackage, hasOwnUpdater, variantOf } from "./lib/assets.js";
import { ATTRIBUTION, FetchFailed, HUB, checkPackage, fetchPackage, fitsChip, matchesSearch, parseCatalog } from "./lib/library.js";

const TOOL = "m-flasher-plus web 1.0.2";
/** Firmware whose update route has run the helper on real hardware. */
const ROUTE_TESTED = new Set(["FM-1_015", "FM-1_016", "FM-1_102", "FM-1_918", "FM-1_900", "FM-1_920", "FM-1_91061", "SMK-37 Pro_016", "SMK-37+VA_102", "SMK-37 Pro_920", "SMK-37 Elite_016"]);
const OTHER_WAYS = "With m-bootloader installed, hold PLAY (SEQ PLAY on the SMK-37) at power-on and choose MIDI MODE. Otherwise, use M-Flasher + on a computer.";
const $ = (id) => document.getElementById(id);

const state = {
  access: null,
  link: null,
  unit: null,
  mode: "none", // firmware, loader, bootloader or stage
  identity: null,
  model: null,
  info: null,
  flash: null,
  analysis: null,
  busy: false,
  backupSaved: false,
  progress: null, // { title, done, total, text }
  plan: null, // sectors about to be written, outlined on the map
  tab: "device",
  catalog: null,
  catalogError: null,
  getting: {}, // firmware id -> percent fetched
  q: "",
  onlyUnit: true,
  betas: true,
  fav: new Set(),
};

const ICON = {
  chip: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><rect x="4" y="4" width="8" height="8"/><path d="M6 1v3M10 1v3M6 12v3M10 12v3M1 6h3M1 10h3M12 6h3M12 10h3"/></svg>',
  boot: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><circle cx="8" cy="8" r="5.5"/><circle cx="8" cy="8" r="2"/></svg>',
  down: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><path d="M8 2v8M4.5 6.5 8 10l3.5-3.5M2.5 13.5h11"/></svg>',
  up: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><path d="M8 11V3M4.5 6.5 8 3l3.5 3.5M2.5 13.5h11"/></svg>',
  fw: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><path d="M2 8h3l1.5-4 3 8L11 8h3"/></svg>',
  out: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><path d="M3 8h9M8.5 4.5 12 8l-3.5 3.5"/></svg>',
};
const esc = (t) => String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function log(text) {
  $("log").textContent += `${new Date().toLocaleTimeString()} ${text}\n`;
  $("log").scrollTop = $("log").scrollHeight;
}

function status(text, kind = "") {
  $("status-text").textContent = text;
  $("status").hidden = !text;
  $("status").dataset.kind = kind;
  $("status").className = `notice ${kind === "error" ? "bad" : kind === "done" ? "good" : ""}`;
  if (text) log(text);
}

/** A finished install's message, which stays when the unit restarts and reappears. */
const finished = () => $("status").dataset.kind === "done";

function progress(title, done, total, text = "") {
  state.progress = { title, done, total, text };
  renderLcd();
}

const hideProgress = () => { state.progress = null; renderLcd(); };

// ---- finding the unit ----

async function connect() {
  try {
    state.access = await navigator.requestMIDIAccess({ sysex: true });
  } catch (cause) {
    status(`The browser didn't give access to MIDI devices (${cause.message}). Allow it, then try again.`, "error");
    return;
  }
  state.access.onstatechange = () => {
    clearTimeout(state.rescanTimer);
    state.rescanTimer = setTimeout(() => { if (!state.busy) scan(); }, 800);
  };
  await scan();
}

function closeLink() {
  state.link?.close();
  state.link = null;
  state.unit = null;
}

function clearUnit() {
  closeLink();
  Object.assign(state, { mode: "none", identity: null, model: null, info: null, flash: null, analysis: null });
}

async function scan() {
  clearUnit();
  const ports = findPorts(state.access);
  const select = $("port-select");
  select.replaceChildren(...ports.map((p, i) => new Option(p.name, i)));
  $("port-row").hidden = ports.length < 2;
  if (!ports.length) {
    if (!finished()) status("No unit found. Is it on, and plugged in with a data cable?");
    render();
    return;
  }
  await open(ports[Number(select.value) || 0]);
}

async function open(port) {
  state.link = new Link(port.input, port.output);
  state.unit = new Unit(state.link);
  state.busy = true;
  try {
    log(`Port: ${port.name}`);
    state.identity = await state.unit.identity();
    if (!state.identity) {
      status(`${port.name} didn't answer. It may be starting up: this firmware can take half a minute to appear.`, "error");
      return;
    }
    log(`Identity: ${state.identity}`);
    state.info = await state.unit.info();
    const name = /^(?:ota-)?(.+)_\d+$/.exec(state.identity)?.[1] ?? null;
    state.model = name;
    if (state.info) state.mode = state.info.kind;
    else state.mode = state.identity.startsWith("ota-") ? "loader" : "firmware";
    // The unit reappears after an install: what the install said should outlast that.
    if (!finished()) status("");
    if (state.info) await readFlash();
  } catch (cause) {
    report(cause);
  } finally {
    state.busy = false;
    render();
  }
}

/** Waits for the unit to come back after a restart, and opens it. */
async function reconnect(seconds, wanted) {
  closeLink();
  const end = Date.now() + seconds * 1000;
  while (Date.now() < end) {
    await new Promise((r) => setTimeout(r, 1000));
    const port = findPorts(state.access)[0];
    if (!port) continue;
    const link = new Link(port.input, port.output);
    const unit = new Unit(link);
    const identity = await unit.identity(800);
    const info = identity ? await unit.info(600) : null;
    link.close();
    if (identity && (!wanted || wanted(identity, info))) return port;
  }
  return null;
}

// ---- reading ----

async function readFlash() {
  const { unit, info } = state;
  const started = Date.now();
  progress("Reading the flash", 0, info.capacity);
  const flash = await unit.readAll(info.capacity, (done, total) => progress("Reading the flash", done, total));
  progress("Checking what was read", info.capacity, info.capacity, "The unit is computing its own CRC");
  const theirs = await unit.check(0, info.capacity);
  const ours = crc32(flash);
  if (theirs !== ours) throw new Error(`what was read doesn't match the chip's CRC (${hex(theirs, 8)}, read ${hex(ours, 8)}). Try again.`);
  log(`Read ${flash.length} bytes in ${((Date.now() - started) / 1000).toFixed(1)} s, CRC-32 ${hex(ours, 8)} matches the chip's.`);
  state.flash = flash;
  state.analysis = analyse(flash, info.jedec, info.key);
  for (const e of state.analysis.evidence) log(`Evidence: ${e[2]}`);
  hideProgress();
}

// ---- saving ----

function saveFile(name, blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

const pad = (n) => String(n).padStart(2, "0");

async function saveBackup() {
  const { flash, analysis, info } = state;
  const now = new Date();
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  const name = `${analysis.board?.key ?? "unknown"}-${stamp}`;
  const fields = {
    model: analysis.modelText,
    bootloader: analysis.bootloaderText,
    firmware: analysis.firmwareText,
    app: analysis.appStatusText,
    "chip key": hex(info.key, 4),
    flash: hex(info.jedec, 6),
    "unit id": info.unitId,
    loader: `${info.kind === "stage" ? "web stage" : "MIDI mode"} ${info.version}`,
    sha256: await sha256Hex(flash),
    saved: stamp,
    tool: TOOL,
  };
  saveFile(`${name}.bin`, new Blob([flash], { type: "application/octet-stream" }));
  await new Promise((r) => setTimeout(r, 400));
  saveFile(`${name}.json`, new Blob([`${JSON.stringify(fields, null, 2)}\n`], { type: "application/json" }));
  state.backupSaved = true;
  log(`Backup saved as ${name}.bin, SHA-256 ${fields.sha256}`);
  status(`Saved ${name}.bin and its .json to your downloads. Keep them safe.`, "done");
  render();
}

// ---- confirming ----

function confirmWrite({ title, text, warnings = [], okLabel, check }) {
  $("confirm-title").textContent = title;
  $("confirm-text").textContent = text;
  $("confirm-warnings").replaceChildren(...warnings.map((w) => {
    const li = document.createElement("li");
    li.textContent = w;
    return li;
  }));
  $("confirm-ok").textContent = okLabel;
  $("confirm-check-row").hidden = !check;
  $("confirm-check").checked = false;
  $("confirm-check-text").textContent = check ?? "";
  $("confirm-ok").disabled = Boolean(check);
  $("confirm-check").onchange = () => { $("confirm-ok").disabled = !$("confirm-check").checked; };
  const dialog = $("confirm");
  dialog.returnValue = "cancel";
  return new Promise((resolve) => {
    dialog.onclose = () => resolve(dialog.returnValue === "ok");
    dialog.showModal();
  });
}

// ---- doing ----

async function run(what) {
  status("");
  state.busy = true;
  render();
  let lock = null;
  const guard = (event) => event.preventDefault();
  window.addEventListener("beforeunload", guard);
  try {
    lock = await navigator.wakeLock?.request("screen").catch(() => null);
    await what();
  } catch (cause) {
    report(cause);
  } finally {
    await lock?.release?.().catch(() => {});
    window.removeEventListener("beforeunload", guard);
    hideProgress();
    state.busy = false;
    render();
  }
}

function report(cause) {
  hideProgress();
  log(`Error: ${cause.stack ?? cause}`);
  if (cause instanceof UnitLost) {
    const after = state.mode === "bootloader" ? "Choose MIDI MODE in the menu again, then reconnect."
      : state.mode === "stage" ? "If it doesn't start normally, switch it off and on, and connect again."
        : "Switch it off and on, and connect again.";
    status(`${cause.message}. ${after}`, "error");
  } else {
    status(`${cause.message}`, "error");
  }
}

async function startTools() {
  const variant = variantOf(state.model);
  if (!variant) {
    status(`This page doesn't know the firmware "${state.identity}", so it can't start the helper on it. ${OTHER_WAYS}`, "error");
    return;
  }
    const warnings = [];
  if (!ROUTE_TESTED.has(state.identity)) warnings.push(`The helper hasn't been tried on ${state.identity} yet. M-VAVE's firmware should take it, but custom firmware may refuse it or behave differently.`);
  const ok = await confirmWrite({
    title: "Start the tools",
    text: "The firmware stores a small helper (about 8 KiB) in its update area and restarts, and the helper then runs from RAM. Your firmware and settings stay. The unit goes back to its firmware when you finish here, or after 5 minutes of no activity.",
    warnings,
    okLabel: "Start",
    check: warnings.length ? "I have read this" : null,
  });
  if (!ok) return;
  await run(async () => {
    // Felucca-family firmware, numbered from 900, wants a flash.bin listed in the package. M-VAVE's and
    // Baud Girl refuse a blank one. A refusal changes nothing, so the other package can follow.
    const family = Number(/_(\d+)$/.exec(state.identity)?.[1]) >= 900;
    let accepted = false;
    for (const kind of family ? ["stageListed", "stage"] : ["stage", "stageListed"]) {
      const pkg = await loadPackage(kind, variant);
      log(`Helper package ${kind}, ${pkg.image.length} bytes`);
      try {
        await state.unit.serveUpdate(pkg.image, {
          finish: FINISH.APP,
          idleMs: 15000,
          onProgress: (done) => progress("Sending the helper", Math.min(done, pkg.image.length), pkg.image.length),
        });
        accepted = true;
        break;
      } catch (cause) {
        if (!(cause instanceof UnitLost)) throw cause;
        log(`Helper: ${cause.message}`);
      }
    }
    if (!accepted) throw new Error(`This firmware didn't accept the helper, and it keeps running. ${OTHER_WAYS}`);
    progress("Waiting for the helper to start", 1, 1, "The unit is restarting");
    const port = await reconnect(45, (identity, info) => info?.kind === "stage");
    if (!port) throw new UnitLost("the helper didn't appear within 45 s");
    state.busy = false;
    await open(port);
    state.busy = true;
    if (state.mode === "stage") status("The tools are running. The unit will go back to its firmware when you restart it.", "done");
  });
}

async function installBootloader() {
  const variant = variantOf(state.model);
  if (!variant) {
    status(`This page doesn't know the firmware "${state.identity}".`, "error");
    return;
  }
  const manifest = await loadManifest();
  const ok = await confirmWrite({
    title: `Install m-bootloader ${manifest.bootloaderVersion}`,
    text: "Installs the open bootloader, through the firmware's update route. Only the boot area changes. It adds hold-REC download mode, a recovery menu and MIDI mode. The firmware stays, and M-Flasher + on a computer can put the stock bootloader back.",
    warnings: [
      state.backupSaved ? null : "You haven't saved a backup in this session. Start the tools first to make one.",
      "An interrupted boot-area write can leave a unit needing a USB_KEY dongle: don't unplug it during this.",
      "FM-1s with the newer UC25HQ80 flash chip have had little testing with m-bootloader.",
    ].filter(Boolean),
    okLabel: "Install",
    check: "I understand",
  });
  if (!ok) return;
  await run(async () => {
    const pkg = await loadPackage("bootloader", variant);
    await state.unit.serveUpdate(pkg.image, {
      finish: FINISH.APP,
      onProgress: (done) => progress("Sending m-bootloader", Math.min(done, pkg.image.length), pkg.image.length),
    });
    progress("Installing", 1, 1, "The unit is writing its bootloader, and restarting");
    const port = await reconnect(60);
    if (port) await rejoin(port);
    status(`m-bootloader was sent. Hold PLAY (SEQ PLAY on the SMK-37) at power-on to see its version (${manifest.bootloaderVersion}).`, "done");
  });
}

async function rejoin(port) {
  state.busy = false;
  await open(port);
  state.busy = true;
}

async function applyPlan(plan) {
  if (!plan.sectors.length) {
    status("Nothing to write: the flash already holds this.", "done");
    return;
  }
  state.plan = new Set(plan.sectors.map((a) => a >> 12));
  renderMap();
  const warnings = [...plan.warnings];
  if (state.info.kind === "stage" && state.analysis.bootloaderKind !== "open") warnings.push("This unit has the stock bootloader, so it has no hold-REC or MIDI mode. If this write is interrupted, it may need M-Flasher + in download mode or a USB_KEY dongle.");
  const ok = await confirmWrite({
    title: plan.title,
    text: `${describePlan(plan)}\n\n${state.backupSaved ? "A backup of this unit was saved in this session." : "Your backup is saved to your downloads first."}\nThe unit checks everything it receives before it erases anything.`,
    warnings,
    okLabel: "Write",
    check: "I understand this changes the unit",
  });
  if (!ok) {
    state.plan = null;
    renderMap();
    return;
  }
  await run(async () => {
    if (!state.backupSaved) await saveBackup();
    const size = Math.max(plan.size, BOOT_AREA + 0x1000);
    const image = packageImage(plan.target, size);
    let pass = 0;
    let last = -1;
    const started = Date.now();
    await state.unit.serveUpdate(image, {
      finish: FINISH.LOADER,
      onProgress: (done) => {
        // The check reads to the end, in order, once; the write starts again from low addresses.
        if (last >= image.length - 0x400 && done < last) pass = 1;
        last = Math.max(done, pass ? 0 : last);
        progress(pass ? "Writing" : "The unit is checking the data", Math.min(done, image.length), image.length,
          pass ? "The unit's own bars are the ones to trust" : "Nothing is erased until this is done");
      },
    });
    log(`Written in ${((Date.now() - started) / 1000).toFixed(0)} s; the unit checked each sector it wrote.`);
    progress("Finishing", 1, 1, "The unit is restarting");
    state.backupSaved = false;
    state.plan = null;
    const port = await reconnect(60);
    if (port) await rejoin(port);
    status(port ? "Done, and the unit is back." : "Done. The unit has restarted, but it hasn't reappeared on USB: this can be normal for firmware without USB MIDI. Check it starts.", "done");
  });
}

async function chooseFile(input) {
  return new Promise((resolve) => {
    input.value = "";
    input.oncancel = () => resolve(null);
    input.onchange = async () => resolve(input.files[0] ? new Uint8Array(await input.files[0].arrayBuffer()) : null);
    input.click();
  });
}

async function restoreBackup() {
  const data = await chooseFile($("backup-file"));
  if (!data) return;
  await attempt(() => applyPlan(planRestore(session(), data, null)));
}

async function installFirmware() {
  const data = await chooseFile($("firmware-file"));
  if (!data) return;
  if (state.mode === "loader") {
    await attempt(() => installOnLoader(data));
    return;
  }
  await attempt(() => applyPlan(planFirmware(session(), data)));
}

async function attempt(what) {
  try {
    await what();
  } catch (cause) {
    report(cause);
  }
}

const session = () => ({ flash: state.flash, key: state.info.key, unitId: state.info.unitId, analysis: state.analysis });

/** An update loader, or MIDI mode from before host reads, takes a package as it is. */
async function installOnLoader(raw) {
  const { parsePackage, packageFlash } = await import("./lib/formats.js");
  const pkg = parsePackage(raw);
  packageFlash(pkg);
  if (state.model && pkg.model !== state.model) throw new Error(`this package is for the ${pkg.model}, the connected unit says ${state.model}`);
  const ok = await confirmWrite({
    title: `Install firmware ${pkg.identity}`,
    text: "Installs the package's firmware. This mode can't read the flash, so no backup can be made first, and the package can't be checked against the unit beyond its model.",
    warnings: ["There is no backup from here."],
    okLabel: "Write",
    check: "I understand",
  });
  if (!ok) return;
  await run(async () => {
    await state.unit.serveUpdate(pkg.image, {
      finish: FINISH.LOADER,
      onProgress: (done) => progress("Installing", Math.min(done, pkg.image.length), pkg.image.length),
    });
    status("Done. The unit is restarting.", "done");
  });
}

function leave(what, message) {
  state.unit[what]();
  status(message, "done");
  clearUnit();
  render();
}

// ---- the library ----

/** The list: this site's /api/catalog (read from the hub with its own User-Agent, at most once a day). */
async function loadLibrary() {
  const local = ["localhost", "127.0.0.1"].includes(location.hostname);
  try {
    let r = await fetch("api/catalog", { headers: { accept: "application/json" } }).catch(() => null);
    // served without the site's functions (python -m http.server, for development): the hub itself
    if ((!r || !r.ok || !r.headers.get("content-type")?.includes("json")) && local) r = await fetch(HUB);
    if (!r?.ok) throw new Error(`the list couldn't be read (${r ? r.status : "no answer"})`);
    state.catalog = parseCatalog(await r.json());
    log(`Firmware list: ${state.catalog.entries.length} firmwares, updated ${state.catalog.updated || "?"}`);
  } catch (cause) {
    state.catalogError = cause.message;
    log(`Firmware list: ${cause.message}`);
  }
  renderLibrary();
}

const FAV_KEY = "m-flasher-plus-web.favorites";
try { state.fav = new Set(JSON.parse(localStorage.getItem(FAV_KEY) ?? "[]")); } catch { state.fav = new Set(); }
function toggleFav(id) {
  if (state.fav.has(id)) state.fav.delete(id); else state.fav.add(id);
  try { localStorage.setItem(FAV_KEY, JSON.stringify([...state.fav])); } catch { /* kept for this visit only */ }
  renderLibrary();
}

/** What INSTALL from the library needs: a unit read here, an FM-1. Says what's missing, or null. */
function cantInstall() {
  if (state.mode === "none") return "Connect the unit first: DEVICE › CONNECT.";
  if (hasOwnUpdater(state.identity)) return "This firmware only updates from its own kind of package: use M-Flasher + on a computer.";
  if (state.mode === "firmware") return "Start the tools first (DEVICE › START THE TOOLS): INSTALL reads the unit and saves a backup before it writes.";
  if (state.mode === "loader") return state.model && !/^(FM-1|MiniFM)$/.test(state.model) ? `The library is for the FM-1; this unit says ${state.model}.` : null;
  if (!state.flash) return "The unit hasn't been read yet.";
  if (state.analysis.board?.key !== "fm1") return "The library is for the FM-1: its firmwares don't run on this unit.";
  return null;
}

async function installFromLibrary(id) {
  const e = state.catalog?.entries.find((x) => x.id === id);
  if (!e || state.busy) return;
  const missing = cantInstall();
  if (missing) {
    state.tab = "device";
    status(missing, "error");
    render();
    return;
  }
  if (state.info && !fitsChip(e, state.info.jedec)) {
    status(`Refused: ${e.name} is marked for the FM-1's older flash chip (0x856014) only; this FM-1 has the newer one. Nothing was written.`, "error");
    return;
  }
  let data = null;
  state.busy = true;
  state.getting[id] = 0;
  render();
  try {
    const got = await fetchPackage(e, {
      log,
      onProgress: (done, total) => {
        state.getting[id] = total ? Math.round((done * 100) / total) : 50;
        progress(`Fetching ${e.name}`, done, total || done, "From its author's GitHub");
        const bar = document.getElementById(`pg-${CSS.escape(id)}`);
        if (bar) bar.style.width = `${state.getting[id]}%`;
      },
    });
    data = got.data;
    log(`${e.name} ${e.version}: SHA-256 matches the list (${got.from})`);
  } catch (cause) {
    log(`Fetch: ${cause.message}`);
    if (!(cause instanceof FetchFailed)) status(cause.message, "error");
    else data = await fallback(e, cause);
  } finally {
    delete state.getting[id];
    state.busy = false;
    hideProgress();
    render();
  }
  if (!data) return;
  state.tab = "device";
  render();
  if (state.mode === "loader") await attempt(() => installOnLoader(data));
  else await attempt(() => applyPlan(planFirmware(session(), data)));
}

/** Plan B: the author's release page, and the file the user downloaded from it, checked against the list. */
async function fallback(e, cause) {
  $("fallback-title").textContent = `Get ${e.name} ${e.version} yourself`;
  $("fallback-why").textContent = `This page couldn't fetch it: ${cause.message}.`;
  $("fallback-sha").textContent = `SHA-256 ${e.sha256}`;
  const link = $("fallback-link");
  link.hidden = !cause.page;
  if (cause.page) link.href = cause.page;
  const dialog = $("fallback");
  dialog.returnValue = "cancel";
  const choice = await new Promise((resolve) => {
    dialog.onclose = () => resolve(dialog.returnValue);
    dialog.showModal();
  });
  if (choice !== "choose") return null;
  const data = await chooseFile($("firmware-file"));
  if (!data) return null;
  try {
    const check = await checkPackage(e, data);
    if (check.ok) {
      log(`${e.name} ${e.version}: the chosen file's SHA-256 matches the list`);
      return data;
    }
    status(`That file isn't ${e.name} ${e.version} as the list names it: ${check.why}. Nothing was written.`, "error");
  } catch (err) {
    status(`That file isn't a firmware package: ${err.message}. Nothing was written.`, "error");
  }
  return null;
}

// ---- showing ----

function action(label, about, onClick, { quiet = false, icon = ICON.fw } = {}) {
  const box = document.createElement("div");
  box.className = "action";
  const button = document.createElement("button");
  button.type = "button";
  button.className = quiet ? "key quiet" : "key";
  button.innerHTML = `${icon} <span></span> <span class="dot"></span>`;
  button.children[1].textContent = label;
  button.disabled = state.busy;
  button.onclick = onClick;
  const p = document.createElement("p");
  p.textContent = about;
  box.append(button, p);
  return box;
}

function fact(list, name, value) {
  const dt = document.createElement("dt");
  dt.textContent = name;
  const dd = document.createElement("dd");
  dd.textContent = value;
  list.push(dt, dd);
}

/** The unit's LCD: what is connected, or what is happening. */
function renderLcd() {
  const a = state.analysis;
  let lines;
  let bar = null;
  if (state.progress) {
    const { title, done, total, text } = state.progress;
    const pct = total ? Math.min(100, Math.round((done * 100) / total)) : 0;
    lines = [title, `${pct} %`, text || "Keep this tab in front", "Don't unplug the unit"];
    bar = pct;
  } else if (state.mode === "none") {
    lines = ["No unit", "USB MIDI", "", state.access ? "Connect it and switch it on" : "Press connect to start"];
  } else if (state.flash) {
    lines = [a.board?.name ?? "Unit", state.info.kind === "stage" ? "Web tools · from RAM" : `MIDI mode ${state.info.version}`,
      a.firmwareText, a.board ? "Ready" : "Model uncertain: read only"];
  } else if (state.mode === "loader") {
    lines = [state.model ?? "Unit", "Update loader", state.identity, "Install firmware"];
  } else {
    lines = [state.model ?? "Unit", "USB MIDI · running", state.identity ?? "", hasOwnUpdater(state.identity) ? "Use a computer" : "Start the tools"];
  }
  const div = (t, cls = "") => `<div class="${cls}">${esc(t) || "&nbsp;"}</div>`;
  $("lcd").innerHTML = div(lines[0], "big") + div(lines[1]) + div(lines[2])
    + (bar == null ? "" : `<div class="bar"><span style="width:${bar}%"></span></div>`)
    + '<div class="rule"></div>' + div(lines[3]);
}

/** The flash, a cell per 4 KiB sector: boot area, in use, empty, and what a plan would write. */
function renderMap() {
  const flash = state.flash;
  let h = "<span></span>";
  for (let c = 0; c < 16; c++) h += `<span>${c.toString(16).toUpperCase()}</span>`;
  for (let r = 0; r < 16; r++) {
    h += `<span>${r.toString(16).toUpperCase()}0</span>`;
    for (let c = 0; c < 16; c++) {
      const i = r * 16 + c;
      let cls = "";
      if (flash && i * 0x1000 < flash.length) {
        if (i < BOOT_AREA >> 12) cls = "s-boot";
        else {
          const s = flash.subarray(i * 0x1000, (i + 1) * 0x1000);
          cls = s.every((b) => b === 0xff) ? "s-empty" : "s-used";
        }
      }
      if (state.plan?.has(i)) cls += " s-plan";
      h += `<div class="c ${cls}"></div>`;
    }
  }
  $("map").innerHTML = h;
}

function chk(ok, title, text) {
  return `<div class="chk"><span class="${ok ? "ok" : "att"}">${ok ? "✓" : "!"}</span><div><b>${esc(title)}</b>${esc(text)}</div></div>`;
}

function renderChecks() {
  const a = state.analysis;
  $("checks-box").hidden = !state.flash;
  if (!state.flash) return;
  const items = [
    [a.bootloaderKind === "open", "Recovery", a.bootloaderKind === "open" ? `${a.bootloaderText} · REC: download mode · PLAY: recovery menu` : "Stock bootloader: no buttons reach download mode if a firmware fails."],
    [state.backupSaved, "Backup", state.backupSaved ? "Saved to your downloads in this session." : "None this session yet. Every write saves one first anyway."],
    [a.appStatus === "OK", "Firmware", `${a.firmwareText} · ${a.appStatusText}`],
    [flashTested(state.info.jedec), "Chip", flashText(state.info.jedec)],
  ];
  const needs = items.filter((i) => !i[0]).length;
  $("needs").textContent = needs ? `${needs} need attention` : "";
  $("checks").innerHTML = items.map((i) => chk(...i)).join("");
}

function renderLibrary() {
  const list = $("list");
  $("attribution").textContent = `${ATTRIBUTION}. Each firmware belongs to its author.`;
  if (!state.catalog) {
    list.innerHTML = `<div class="empty">${esc(state.catalogError ? `The firmware list couldn't be read: ${state.catalogError}` : "Loading the firmware list…")}</div>`;
    $("count").textContent = "";
    return;
  }
  const all = state.catalog.entries;
  let items = all.filter((e) => matchesSearch(state.q, e) && (state.betas || !e.beta));
  const jedec = state.info?.jedec;
  let hidden = 0;
  if (state.onlyUnit && jedec != null) {
    const fits = items.filter((e) => fitsChip(e, jedec));
    hidden = items.length - fits.length;
    items = fits;
  }
  items.sort((a, b) => (state.fav.has(b.id) - state.fav.has(a.id)) || (b.stock - a.stock) || a.name.localeCompare(b.name));
  $("count").textContent = `${items.length} of ${all.length}`;
  $("hidden-note").hidden = !hidden;
  $("hidden-note").textContent = `${hidden} more hidden by this unit only (made for the older flash chip)`;
  list.innerHTML = items.map((e) => {
    const pct = state.getting[e.id];
    const where = e.elsewhere ? "" : e.repo ? `<a href="https://github.com/${esc(e.repo)}" target="_blank" rel="noopener">${esc(e.repo)}</a>` : e.stock ? "m-vave/fm1_open" : "";
    const act = pct != null ? `<div class="prog" aria-label="Fetching"><span id="pg-${esc(e.id)}" style="width:${pct}%"></span></div>`
      : e.elsewhere ? `<a class="key dark" href="${esc(e.site ?? "#")}" target="_blank" rel="noopener">Author's installer</a>`
        : `<button class="key" type="button" data-install="${esc(e.id)}" ${state.busy ? "disabled" : ""}>Install</button>`;
    return `<div class="fw">
      <button class="star" type="button" data-fav="${esc(e.id)}" aria-pressed="${state.fav.has(e.id)}" aria-label="Favorite ${esc(e.name)}">★</button>
      <div><h4>${esc(e.name)}<small>${esc(e.version)}</small></h4>
        <p>${esc(e.author)} · ${esc(e.license)}${e.kinds.length ? ` · ${esc(e.kinds.join(", ").toUpperCase())}` : ""}${e.beta ? " · BETA" : ""}${e.chip === "old" ? ' · <span class="old">OLDER CHIP ONLY</span>' : ""}${where ? ` · ${where}` : ""}</p></div>
      <div class="acts">${act}</div></div>`;
  }).join("") || '<div class="empty">No firmware matches.</div>';
  list.querySelectorAll("[data-install]").forEach((b) => { b.onclick = () => installFromLibrary(b.dataset.install); });
  list.querySelectorAll("[data-fav]").forEach((b) => { b.onclick = () => toggleFav(b.dataset.fav); });
}

function render() {
  for (const t of ["device", "library", "about"]) {
    $(`tab-${t}`).hidden = state.tab !== t;
    $(`t-${t}`).setAttribute("aria-selected", String(state.tab === t));
  }
  $("connect-button").innerHTML = `${ICON.chip} <span>${state.access ? "Look again" : "Connect"}</span> <span class="dot"></span>`;
  $("connect-button").disabled = state.busy;
  $("info").hidden = state.mode === "none";
  const facts = [];
  const notes = [];
  if (hasOwnUpdater(state.identity)) {
    fact(facts, "Running", `${state.mode === "loader" ? "its updater" : "firmware"} ${state.identity}`);
    notes.push("This firmware only updates from its own kind of package, so this page can't start the tools on it. Your firmware is unchanged. Use M-Flasher + on a computer, or m-bootloader 2.2's MIDI MODE (hold SEQ PLAY at power-on).");
    if (state.mode === "loader") notes.push("It's in its updater now. To leave, switch it off and on.");
  } else if (state.mode === "firmware") {
    fact(facts, "Running", `firmware ${state.identity}`);
    notes.push("A browser can't read the flash through this firmware. Start the tools to back up, restore or install firmware.");
  } else if (state.mode === "loader") {
    fact(facts, "Running", `update loader or older MIDI mode (${state.identity})`);
    notes.push("This can install firmware, but it can't read the flash, so no backup is possible here.");
  } else if (state.flash) {
    const a = state.analysis;
    fact(facts, "Running", state.info.kind === "stage" ? `web tools ${state.info.version}, from RAM` : `MIDI mode (${state.info.version})`);
    fact(facts, "Model", a.modelText);
    fact(facts, "Bootloader", a.bootloaderText);
    fact(facts, "Firmware", a.firmwareText);
    fact(facts, "Firmware state", a.appStatusText);
    fact(facts, "Chip key", hex(state.info.key, 4));
    fact(facts, "Flash chip", flashText(state.info.jedec));
    fact(facts, "Unit ID", state.info.unitId);
    if (!a.board) notes.push("The model is uncertain, so this page won't write anything to this unit.");
    if (!flashTested(state.info.jedec)) notes.push(`This flash chip (${hex(state.info.jedec, 6)}) has had little testing with m-bootloader.`);
    if (a.appStatus !== "OK") notes.push(`The firmware: ${a.appStatusText}.`);
  }
  $("unit-facts").replaceChildren(...facts);
  $("unit-notes").replaceChildren(...notes.map((n) => {
    const li = document.createElement("li");
    li.textContent = n;
    return li;
  }));
  const toLibrary = () => { state.tab = "library"; render(); };
  const toComputer = () => leave("downloadMode", "Asked for download mode. Open M-Flasher + on a computer.");
  const list = [];
  if (hasOwnUpdater(state.identity)) {
    list.push(action("Switch to download mode", "For M-Flasher + on a computer. A browser can't use this mode.", toComputer, { icon: ICON.out }));
  } else if (state.mode === "firmware") {
    list.push(action("Start the tools", "Back up, restore, or install firmware. Your firmware stays, and the unit goes back to it afterwards.", startTools, { icon: ICON.chip }));
    list.push(action("Install m-bootloader", "Adds hold-REC download mode, a recovery menu, and MIDI mode for when firmware won't start.", installBootloader, { quiet: true, icon: ICON.boot }));
    list.push(action("Switch to download mode", "For M-Flasher + on a computer. A browser can't use this mode.", toComputer, { quiet: true, icon: ICON.out }));
  } else if (state.mode === "loader") {
    list.push(action("Flash firmware", "From the LIBRARY, fetched from its author's GitHub.", toLibrary));
    list.push(action("Install from a file…", "An .fwsc package you have.", installFirmware, { quiet: true }));
    list.push(action("Switch to download mode", "For M-Flasher + on a computer.", toComputer, { quiet: true, icon: ICON.out }));
  } else if (state.flash) {
    list.push(action("Backup full flash", "The whole flash, as read, to your downloads, in M-Flasher +'s format.", saveBackup, { icon: ICON.down }));
    if (state.analysis.board) {
      list.push(action("Restore full flash…", "Firmware, settings and presets from a backup of this unit. Checks everything before erasing anything.", restoreBackup, { icon: ICON.up }));
      if (state.analysis.board.key === "fm1") list.push(action("Flash firmware", "From the LIBRARY, fetched from its author's GitHub.", toLibrary));
      list.push(action("Install from a file…", "An .fwsc package for this model.", installFirmware, { quiet: true }));
    }
    list.push(action("Restart", "Leaves this mode, and starts the firmware.", () => leave("restart", "Restarting."), { quiet: true, icon: ICON.out }));
    list.push(action("Switch to download mode", "For M-Flasher + on a computer.", toComputer, { quiet: true, icon: ICON.out }));
  }
  $("action-list").replaceChildren(...list);
  renderLcd();
  renderMap();
  renderChecks();
  renderLibrary();
  document.body.dataset.mode = state.mode;
  document.body.dataset.busy = String(state.busy);
}

// #library or #about opens that tab; the tabs keep the address up to date
const TABS = ["device", "library", "about"];
if (TABS.includes(location.hash.slice(1))) state.tab = location.hash.slice(1);
document.querySelectorAll(".tabs button").forEach((b) => {
  b.onclick = () => {
    state.tab = b.dataset.tab;
    history.replaceState(null, "", state.tab === "device" ? location.pathname : `#${state.tab}`);
    render();
  };
});
$("q").oninput = (e) => { state.q = e.target.value; renderLibrary(); };
$("only").onchange = (e) => { state.onlyUnit = e.target.checked; renderLibrary(); };
$("betas").onchange = (e) => { state.betas = e.target.checked; renderLibrary(); };
$("connect-button").onclick = () => {
  status("");
  return state.access ? scan() : connect();
};
$("port-select").onchange = async () => {
  closeLink();
  const ports = findPorts(state.access);
  await open(ports[Number($("port-select").value)]);
};
document.addEventListener("visibilitychange", () => {
  if (document.hidden && state.busy) log("This tab went to the background: browsers slow it down, so an update may take far longer. Bring it back.");
});
if (!navigator.requestMIDIAccess) {
  $("unsupported").hidden = false;
  $("connect-button").hidden = true;
}
render();
loadLibrary();
