//! The window: an FM-synth front panel with the flash map, drawn with egui on
//! a fixed 1536 x 1024 board scaled to the window. All state comes from the
//! controller; this module only draws it and turns clicks into actions.

use crate::controller::{AlertKind, BootSource, Controller, FileKind, Mark, Mode, Phase, PlanKind, Shared, Stage};
use crate::sectormap::{Region, region_ranges};
use eframe::egui::{
    self, Align2, Color32, CornerRadius, FontFamily, FontId, Mesh, Pos2, Rect, Sense,
    Shape, Stroke, StrokeKind, TextureHandle, TextureOptions, TextureWrapMode, pos2, vec2,
};
use std::cell::RefCell;

const W: f32 = 1536.0;
const H: f32 = 1024.0;

const fn hex(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}
// FirmWave · Neo Brutalism, Option A: the Foundations' tokens (Figma 283:9836), `fw::` in the code
use crate::fw;
const BG: Color32 = fw::BG_APP;
const BG2: Color32 = fw::BG_APP;
const INK: Color32 = fw::INK_PRIMARY;
const DIM: Color32 = fw::INK_MUTED;
const LAV: Color32 = fw::INK_PRIMARY;      // section labels: black, Lexend Mega
const LAVLINE: Color32 = fw::BORDER_EMPTY; // rules
const MINT: Color32 = fw::INK_OK;          // done, checked, the percentage
const KEY_TOP: Color32 = fw::SURFACE_CARD;
const KEY_BOT: Color32 = fw::SURFACE_CARD;
const KEYLINE: Color32 = fw::BORDER_DEFAULT;
const KEYINK: Color32 = fw::INK_PRIMARY;
const LCD1: Color32 = fw::LCD_NORMAL;
const LCD2: Color32 = fw::LCD_NORMAL;
const LCDINK: Color32 = fw::INK_PRIMARY;
const NOW: Color32 = fw::ACCENT_BRAND;
const WARN: Color32 = fw::ACCENT_BRAND;
const BAD: Color32 = fw::MAP_BOOT;

fn region_color(r: Region) -> Color32 {
    match r {
        Region::Boot => fw::MAP_BOOT,
        Region::Firmware => fw::MAP_FIRMWARE,
        Region::Settings => fw::MAP_SETTINGS,
        Region::Pairings => fw::MAP_PAIRINGS,
        Region::Calibration => fw::CREAM_50,
        Region::Presets => fw::ORANGE_300,
        Region::Sequences => fw::LILAC,
        Region::Sounds => fw::MAP_SOUNDS,
        Region::Mac => fw::MAP_MAC,
        Region::Free => fw::MAP_FREE,
    }
}

/// The left column: the unit and its operations, or the firmware library.
#[derive(PartialEq, Clone, Copy)]
enum Tab {
    Device,
    Library,
}

/// Rows of the Library tab shown at once.
const LIB_ROWS: usize = 8;
/// Where the Library's rows start, and a row's height with its gap (board units).
const LIB_TOP: f32 = 470.0;
const LIB_STEP: f32 = 61.0;

#[derive(PartialEq)]
enum Dialog {
    None,
    /// Library: delete a firmware's downloads (id, name, versions), asked first.
    Delete(String, String, String),
    /// Firmware on a unit with the stock bootloader: m-bootloader first.
    TwoStep,
    Review,
    About,
    Model,
}

pub struct App {
    ctl: Controller,
    dialog: Dialog,
    noise: Option<TextureHandle>,
    /// When an inactive key was last pressed: the LCD's bottom line, which
    /// says what is needed, flashes for a moment.
    nudged: f64,
    /// m-bootloader was accepted when recommended: its review opens once planned.
    review_when_planned: bool,
    fonts: crate::fonts::Fonts,
    tab: Tab,
    /// Library tab: only what this unit's flash chip takes; betas too; the first row shown.
    lib_compatible: bool,
    lib_beta: bool,
    lib_first: usize,
    /// Scrolling not yet a whole row.
    lib_scroll: f32,
    /// The Library tab's search: name, author, id or kind.
    lib_query: String,
    /// Library: only the starred firmwares.
    lib_favorites: bool,
    /// The operation chosen on the checked unit (its form shows): none until one is.
    op_open: Option<Mode>,
}

/// FirmWave's home: source, releases and issues (the About dialog).
const REPO: &str = "https://github.com/wellingtonsaam/firmwave-mflasher-plus";

/// How long the LCD's bottom line flashes after an inactive key is pressed (s).
const NUDGE: f64 = 1.2;

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let fonts = crate::fonts::Fonts::default();
        fonts.install(&cc.egui_ctx);
        // FIRMWAVE_DIALOG=about opens the About dialog at start (for screenshots)
        let dialog = if crate::migrate::env_str("DIALOG").as_deref() == Some("about") { Dialog::About } else { Dialog::None };
        // FIRMWAVE_TAB=library opens the Library tab at start (for screenshots)
        let tab = if crate::migrate::env_str("TAB").as_deref() == Some("library") { Tab::Library } else { Tab::Device };
        let ctl = Controller::new(cc.egui_ctx.clone());
        if tab == Tab::Library {
            ctl.load_library();
        }
        App { ctl, dialog, noise: None, nudged: f64::NEG_INFINITY, review_when_planned: false, fonts, tab,
              lib_compatible: true, lib_beta: true, lib_first: 0, lib_scroll: 0.0, lib_query: String::new(), lib_favorites: false, op_open: None }
    }
}

/// The LCD's bottom line for the current state.
fn prompt(s: &Shared, chosen: bool) -> String {
    let n_done = s.marks.values().filter(|m| matches!(m, Mark::Written | Mark::Verified)).count();
    match (&s.device, s.phase) {
        (None, Phase::Connecting) => "DO NOT UNPLUG OR SWITCH OFF".into(),
        (None, _) if s.multiple => "CONNECT ONLY ONE UNIT".into(),
        (None, _) if s.midi.is_some() => format!("{} RUNNING · PRESS CHECK FM1 STATUS",
                                                 crate::controller::unit_name("", s.midi.as_deref().unwrap_or("")).to_uppercase()),
        (None, _) => "CONNECT IT AND PRESS CHECK FM1 STATUS".into(),
        (Some(_), Phase::Working) if s.mode == Mode::Backup => "SAVING FULL BACKUP · 1 MiB".into(),
        (Some(_), Phase::Working) if s.stage[2] == Stage::Idle => "SAVING A BACKUP FIRST · DO NOT SWITCH OFF".into(),
        (Some(_), Phase::Working) => {
            let total = s.plan.as_ref().map(|p| p.count).unwrap_or(0);
            let verb = if s.stage[3] == Stage::Active { "VERIFYING" } else { "WRITING" };
            format!("{verb} 0x{:05X} · {n_done} / {total} · DO NOT SWITCH OFF", s.cur.unwrap_or(0))
        }
        (Some(_), Phase::Done) if s.mode == Mode::Backup => format!("BACKUP SAVED · {}", s.backup_saved.clone().unwrap_or_default()),
        (Some(_), Phase::Done) => format!("DONE · {} SECTORS VERIFIED · SWITCH OFF AND ON", s.plan.as_ref().map(|p| p.count).unwrap_or(0)),
        (Some(d), _) if !chosen && s.mode != Mode::Bootloader => {
            if d.boot_open { "CHECKED · CHOOSE AN OPERATION".into() } else { "INSTALL M-BOOTLOADER FIRST: IT PROTECTS AGAINST BRICKS".into() }
        }
        (Some(_), _) => match &s.plan {
            None => "CHOOSE AN OPERATION BELOW".into(),
            Some(p) => match p.kind {
                PlanKind::Refused => format!("REFUSED · {}", p.title.to_uppercase()),
                PlanKind::Need => p.title.to_uppercase(),
                PlanKind::Backup => "SAVE FULL BACKUP · 1 MiB · WRITES NOTHING".into(),
                PlanKind::Write if p.count == 0 => "NOTHING TO WRITE · ALREADY ON THE UNIT".into(),
                PlanKind::Write => {
                    let what = match s.mode {
                        Mode::Firmware => format!("INSTALL {}", s.file(FileKind::Firmware).unwrap_or_default().trim_end_matches(".fwsc")),
                        Mode::Restore => format!("RESTORE {}", s.file(FileKind::Restore).unwrap_or_default().trim_end_matches(".bin")),
                        _ => match s.boot_source {
                            BootSource::Open => format!("M-BOOTLOADER {}", s.open_version),
                            BootSource::Stock => "STOCK BOOTLOADER".into(),
                            BootSource::File => format!("BOOTLOADER {}", s.file(FileKind::BootFile).unwrap_or_default()),
                        },
                    };
                    let line = format!("{what} · {} SECTORS ({} KiB) · {}", p.count, p.count * 4, p.short);
                    if line.chars().count() <= 60 { line } else { format!("{what} · {} SECTORS · {}", p.count, p.short) }
                }
            },
        },
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let st = self.ctl.snapshot();
        let ctx = ui.ctx().clone();
        preview_shot(&ctx);
        let screen = ctx.content_rect();
        let s = (screen.width() / W).min(screen.height() / H);
        let board = Board { s, o: screen.center() - vec2(W * s, H * s) / 2.0 };
        let painter = ui.painter().clone();
        // letters the fonts lack, from the texts drawn: looked for after the frame
        let fonts = std::mem::take(&mut self.fonts);
        let missing = RefCell::new(vec![]);
        let p = Painter { p: &painter, b: &board, fonts: &fonts, missing: &missing };
        let noise = self.noise.get_or_insert_with(|| noise_texture(&ctx)).clone();
        let dev = st.device.as_ref();
        let busy = st.busy;
        if st.boot_review && !st.busy && st.alert.is_none() && self.ctl.take_boot_review() {
            // INSTALL M-BOOTLOADER's unit is read: chosen and reviewed as any write
            self.op_open = None;
            self.review_when_planned = self.ctl.answer_boot_suggestion(true);
        }
        if self.review_when_planned && !st.busy {
            // planned (installing m-bootloader, chosen when it was recommended): review it as any write
            if st.mode == Mode::Bootloader && st.phase == Phase::Ready && st.alert.is_none()
                && st.plan.as_ref().is_some_and(|p| p.ready) && self.dialog == Dialog::None {
                self.dialog = Dialog::Review;
            }
            self.review_when_planned = false;
        }
        if st.library_review && !st.busy && st.alert.is_none() && self.dialog == Dialog::None {
            // library INSTALL planned: its review opens right away, over the LIBRARY tab (or the two steps
            // first, REQ-BOOT-01); the Device tab's firmware form shows the same choice
            self.ctl.clear_library_review();
            self.op_open = Some(Mode::Firmware);
            if st.mode == Mode::Firmware && st.phase == Phase::Ready && st.plan.as_ref().is_some_and(|p| p.ready) {
                self.dialog = if st.boot_first.is_some() { Dialog::TwoStep } else { Dialog::Review };
            }
        }
        if dev.is_none() {
            // no unit checked: no operation chosen
            self.op_open = None;
        }
        if st.restore_review && !st.busy && st.alert.is_none() && self.dialog == Dialog::None {
            // a backup chosen in the file dialog: its review opens right away (Figma 8b)
            self.ctl.clear_restore_review();
            if st.mode == Mode::Restore && st.phase == Phase::Ready && st.plan.as_ref().is_some_and(|p| p.ready) {
                self.dialog = Dialog::Review;
            }
        }
        if st.review_next && !st.busy && st.alert.is_none() && self.dialog == Dialog::None {
            // step 1 of 2 written: the firmware's review, as any write
            self.ctl.clear_review_next();
            if st.mode == Mode::Firmware && st.phase == Phase::Ready && st.plan.as_ref().is_some_and(|p| p.ready) {
                self.dialog = Dialog::Review;
            }
        }
        let modal = self.dialog != Dialog::None || st.alert.is_some() || st.suggest_boot.is_some() || st.how_to.is_some();
        let active = !modal && !busy; // clicks allowed
        let clicked = |id: &str, r: Rect| -> (bool, bool) {
            let resp = ui.interact(r, ui.id().with(id), Sense::click());
            (resp.clicked(), resp.hovered())
        };

        // ---- background ----
        p.rect(screen, BG2);
        let _ = &noise; // the grain belongs to the old dark panel: FirmWave's ground is flat

        // ---- masthead ----
        let origin = board.p(40.0, 116.0);
        let _ = origin;
        // the logo (Figma DS · Logo, 308:12075): "FirmWave" in Lexend Mega Black, letter spacing -20%, then the app
        // icon: the coral square with a 3 px black outline, a hard shadow and the black wave (DS · Container)
        let ww = p.text(38.0, 76.0, Align2::LEFT_CENTER, "FirmWave", "logo", 36.0, INK, -7.2);
        // the Logo's "Transition subtitle" (Figma 308:12075): the old name, while people still look for it
        p.text(40.0, 106.0, Align2::LEFT_CENTER, "formerly M-Flasher+", "plex", 13.0, DIM, 0.0);
        let ix = 38.0 + ww + 13.0;
        let icon = board.r(ix, 84.0 - 38.0, 76.0, 76.0);
        p.card(icon, fw::CORAL, 19.0, 6.0);
        wave_circuit(&p, ix, 84.0 - 38.0, 76.0);
        let ww = ww + 13.0 + 76.0;
        p.text(38.0 + ww + 24.0, 72.0, Align2::LEFT_CENTER, "USB FIRMWARE UTILITY", "cond", 15.0, INK, 1.8);
        p.text(38.0 + ww + 24.0, 94.0, Align2::LEFT_CENTER, "FOR MUSIC CREATIVES · FM-1 AND SMK-37", "tech", 12.0, DIM, 1.2);
        // the header's pills (Figma DS · Tab): DEVICE and LIBRARY are tabs, BACKUPS opens the folder, ABOUT the dialog;
        // the active tab purple with its hard shadow, the version a yellow tag
        let vw = p.measure(&format!("v{}", env!("CARGO_PKG_VERSION")), "lcd", 13.0, 0.0) + 18.0;
        let vr = board.r(1500.0 - vw, 66.0, vw, 28.0);
        p.card(vr, fw::ACCENT_PRIMARY, 6.0, 0.0);
        p.text(vr.center().x / s - board.o.x / s, 80.0, Align2::CENTER_CENTER, &format!("v{}", env!("CARGO_PKG_VERSION")), "lcd", 13.0, INK, 0.0);
        let pills = [(Some(Tab::Device), "DEVICE"), (Some(Tab::Library), "LIBRARY"), (None, "BACKUPS"), (None, "ABOUT")];
        let pw = |n: &str| p.measure(n, "cond", 14.0, 1.2) + 36.0;
        let mut tx = 1500.0 - vw - 16.0 - pills.iter().map(|(_, n)| pw(n) + 10.0).sum::<f32>();
        for (tab, name) in pills {
            let w = pw(name);
            let on = tab.is_some_and(|t| self.tab == t);
            let r = board.r(tx, 62.0, w, 36.0);
            let (c, h) = clicked(&format!("tab-{name}"), r);
            // the active tab is cyan, as the DS Tab component's Active state (color/op/save)
            p.card(r, if on { fw::CYAN } else { fw::SURFACE_CARD }, 18.0, if on || (h && !modal) { 3.0 } else { 0.0 });
            p.text(tx + w / 2.0, 80.0, Align2::CENTER_CENTER, name, "cond", 14.0, INK, 1.2);
            if c && !modal {
                match tab {
                    Some(t) if !on => {
                        self.tab = t;
                        if t == Tab::Library {
                            self.ctl.load_library();
                        }
                    }
                    Some(_) => {}
                    None if name == "BACKUPS" => self.ctl.open_backups(),
                    None => self.dialog = Dialog::About,
                }
            }
            tx += w + 10.0;
        }
        p.hline(36.0, 1500.0, 128.0, LAVLINE);
        p.vline(913.0, 142.0, 988.0, LAVLINE);

        // ---- LCD ----
        p.label(38.0, 148.0, "CONNECTED DEVICE", None);
        let lcd = board.r(38.0, 166.0, 853.0, 196.0);
        p.card(lcd, LCD1, 12.0, 6.0);
        // FirmWave LCD (Figma Option A, W1): text only, the title big, two lines, a rule and what to do
        let _ = lcd_icon;
        painter.line_segment([board.p(70.0, 306.0), board.p(860.0, 306.0)], Stroke::new(3.0 * s, LCDINK));
        let (l1, l2, l3) = match dev {
            Some(d) => (d.model.replace(" (probably)", "").to_uppercase(),
                        if d.certain { "USB CONNECTED · DOWNLOAD MODE".to_string() } else { "MODEL NOT CERTAIN · CHECK BELOW".into() },
                        "FLASH 1 MiB".to_string()),
            None if st.phase == Phase::Connecting => ("READING".into(), "FLASH READ · CHECKING".into(), format!("{}%", (st.pct * 100.0) as u32)),
            None => ("NO UNIT".into(), if st.multiple { "MORE THAN ONE UNIT".into() } else { "WAITING FOR DOWNLOAD MODE".into() }, "USB".into()),
        };
        p.text_fit(70.0, 206.0, &l1, "lcd", 44.0, 780.0, LCDINK, 1.0);
        p.text_fit(70.0, 248.0, &l2, "lcd", 20.0, 780.0, LCDINK, 0.6);
        p.text_fit(70.0, 280.0, &l3, "lcd", 20.0, 780.0, LCDINK, 0.6);
        let line = prompt(&st, self.op_open.is_some());
        let alert_ink = st.plan.as_ref().is_some_and(|p| p.kind == PlanKind::Refused);
        let mut ink = if alert_ink { hex(0x5b1206) } else { LCDINK };
        // after an inactive key: the line shakes, and flashes inverted three times
        let now = ctx.input(|i| i.time);
        let t = now - self.nudged;
        let mut dx = 0.0;
        if (0.0..NUDGE).contains(&t) {
            dx = (t * 45.0).sin() as f32 * 5.0 * (1.0 - t as f32 / 0.45).max(0.0);
            if ((t / 0.2) as i64) % 2 == 0 {
                p.rect(board.r(62.0, 320.0, 800.0, 28.0), ink);
                ink = LCD1;
            }
            ctx.request_repaint_after(std::time::Duration::from_millis(16));
        }
        let pw = p.text_fit(70.0 + dx, 334.0, &line, "lcd", 20.0, 760.0, ink, 0.6);
        if (now * 2.0) as i64 % 2 == 0 {
            p.rect(board.r(70.0 + dx + pw + 6.0, 324.0, 11.0, 20.0), ink);
        }
        ctx.request_repaint_after(std::time::Duration::from_millis(500));

        if self.tab == Tab::Device {
        // ---- check and protect (Figma: Device scenarios): the unit is checked before anything else ----
        let half = (853.0 - 14.0) / 2.0;
        let checking = st.phase == Phase::Connecting || (busy && dev.is_none());
        let cr = board.r(38.0, 384.0, half, 62.0);
        let (cc, ch) = clicked("check", cr);
        key(&p, cr, dev.is_none() && !checking, ch && active, !active);
        draw_icon(&p, Icon::Chip, 60.0, 399.0, 32.0, KEYINK);
        p.text(110.0, 415.0, Align2::LEFT_CENTER, if checking { "CHECKING…" } else { "CHECK FM1 STATUS" }, "cond", 19.0, KEYINK, 1.2);
        led(&p, board.p(38.0 + half - 20.0, 400.0), dev.is_none() && !checking);
        if cc && active {
            self.ctl.check();
        } else if cc && !modal {
            self.nudged = now;
        }
        let open = dev.is_some_and(|d| d.boot_open);
        let bx = 38.0 + half + 14.0;
        let br = board.r(bx, 384.0, half, 62.0);
        let (bc, bh) = clicked("installboot", br);
        // one click: with no unit read, INSTALL M-BOOTLOADER reads it first (as CHECK FM1 STATUS)
        let can_boot = active && !open && !checking;
        key(&p, br, !open && !checking, bh && can_boot, !can_boot);
        draw_icon(&p, Icon::Boot, bx + 22.0, 399.0, 32.0, KEYINK);
        if open {
            // "m-bootloader 2.4-395e6cc": its version
            let v = dev.map(|d| d.bootloader.split_whitespace().nth(1).unwrap_or("").split('-').next().unwrap_or("").to_string())
                .unwrap_or_default();
            p.text(bx + 72.0, 404.0, Align2::LEFT_CENTER, &format!("M-BOOTLOADER {v}"), "cond", 16.0, KEYINK, 1.0);
            p.text(bx + 72.0, 426.0, Align2::LEFT_CENTER, "INSTALLED ✓", "cond", 16.0, KEYINK, 1.0);
        } else {
            p.text(bx + 72.0, 415.0, Align2::LEFT_CENTER, "INSTALL M-BOOTLOADER", "cond", 19.0, KEYINK, 1.2);
        }
        led(&p, board.p(bx + half - 20.0, 400.0), !open && !checking);
        if bc && can_boot && dev.is_some() {
            // reviewed as any write once planned
            self.op_open = None;
            self.review_when_planned = self.ctl.answer_boot_suggestion(true);
        } else if bc && can_boot {
            self.ctl.install_bootloader();
        } else if bc && !modal {
            self.nudged = now;
        }
        if dev.is_some() && !open {
            // FirmWave Safety banner (Figma Option A): an orange bar, a black "!" square, the label and the reason
            p.pill(board.r(38.0, 462.0, 853.0, 36.0), fw::STATUS_WARN, 10.0, 2.5, 0.0);
            alert_box(&p, 54.0, 470.0, 20.0);
            let lw = p.text(88.0, 480.0, Align2::LEFT_CENTER, "SAFETY CHECK", "label", 12.0, INK, 1.6);
            p.text_fit(88.0 + lw + 16.0, 480.0, "You don't have m-bootloader · It's a safeguard to prevent bricks",
                       "tech", 15.0, 853.0 - lw - 80.0, INK, 0.0);
        }

        if let Some(d) = dev {
        // ---- device info: once checked ----
        p.label(38.0, 516.0, "DEVICE INFO", None);
        p.card(board.r(38.0, 532.0, 853.0, 114.0), fw::SURFACE_CARD, 12.0, 5.0);
        let rows: [[(&str, String, Color32); 2]; 3] = [
            [("MODEL", d.model.to_uppercase(), if d.certain { INK } else { WARN }), ("BOOTLOADER", d.bootloader.clone(), if d.boot_open { INK } else { WARN })],
            [("FIRMWARE", d.firmware_shown(), INK), ("FLASH CHIP", d.flash_label.clone(), INK)],
            [("FIRMWARE CHECK", d.app.clone(), if d.app_ok { MINT } else { BAD }), ("CHIP KEY", d.chip_key.clone(), if d.chip_key_ok { INK } else { WARN })],
        ];
        for (i, row) in rows.iter().enumerate() {
            let y = 551.0 + i as f32 * 37.0;
            if i > 0 {
                painter.line_segment([board.p(56.0, y - 18.5), board.p(873.0, y - 18.5)], Stroke::new(2.0 * s, fw::BORDER_DEFAULT));
            }
            for (j, (k, v, c)) in row.iter().enumerate() {
                let x = if j == 0 { 58.0 } else { 478.0 };
                p.text(x, y, Align2::LEFT_CENTER, k, "tech", 12.0, DIM, 1.2);
                // what needs a look, and the firmware's check, in a pill of their status colour
                let pill = match (*k, *c) {
                    (_, c) if c == WARN => Some(fw::STATUS_WARN),
                    ("FIRMWARE CHECK", c) if c == MINT => Some(fw::STATUS_OK),
                    (_, c) if c == BAD => Some(fw::CORAL),
                    _ => None,
                };
                if let Some(f) = pill {
                    let w = p.measure(v, "plex", 14.0, 0.0).min(240.0);
                    p.pill(board.r(x + 142.0, y - 12.0, w + 18.0, 24.0), f, 6.0, 2.0, 0.0);
                }
                p.text_fit(x + 151.0, y, v, "plex", 14.0, 240.0, INK, 0.0);
            }
        }
        if !d.certain {
            let r = board.r(54.0, 539.0, 400.0, 26.0);
            let (c, h) = clicked("model", r);
            if h && active {
                painter.rect_stroke(r, CornerRadius::same((4.0 * s) as u8), Stroke::new(2.0 * s, INK), StrokeKind::Inside);
            }
            p.text(446.0, 551.0, Align2::RIGHT_CENTER, "CHOOSE ▸", "tech", 12.0, INK, 1.0);
            if c && active {
                self.dialog = Dialog::Model;
            }
        }

        // ---- operations: backup saves at once; restore and firmware open their form ----
        p.label(38.0, 670.0, "OPERATIONS", None);
        let keys = [
            // labels as in Figma (W8 · 101:4991)
            (Mode::Backup, "SAVE", "BACKUP", Icon::Backup),
            (Mode::Restore, "RESTORE", "BACKUP", Icon::Restore),
            (Mode::Firmware, "INSTALL", "FIRMWARE", Icon::Chip),
        ];
        let kw = (853.0 - 2.0 * 14.0) / 3.0;
        for (i, (mode, t1, t2, icon)) in keys.iter().enumerate() {
            let x = 38.0 + i as f32 * (kw + 14.0);
            let r = board.r(x, 684.0, kw, 64.0);
            let (c, h) = clicked(&format!("op{i}"), r); // ids must be unique: two keys say BACKUP
            let on = self.op_open == Some(*mode);
            // FirmWave Operation key (Figma Option A): SAVE cyan, RESTORE pink, INSTALL lilac
            let fill = match mode { Mode::Backup => fw::OP_SAVE, Mode::Restore => fw::OP_RESTORE, _ => fw::OP_INSTALL };
            key_fill(&p, r, fill, h && active, !active && !on);
            draw_icon(&p, *icon, x + 20.0, 699.0, 34.0, KEYINK);
            p.text(x + 66.0, 705.0, Align2::LEFT_CENTER, t1, "cond", 17.0, KEYINK, 1.0);
            p.text(x + 66.0, 727.0, Align2::LEFT_CENTER, t2, "cond", 17.0, KEYINK, 1.0);
            led(&p, board.p(x + kw - 24.0, 716.0), on);
            if c && active {
                self.op_open = Some(*mode);
                if *mode == Mode::Backup {
                    self.ctl.save_backup();
                } else {
                    self.ctl.select_mode(*mode);
                }
            } else if c && !modal {
                self.nudged = now;
            }
        }

        // ---- the chosen operation's form ----
        let form = match self.op_open {
            Some(Mode::Firmware) if st.mode == Mode::Firmware => Some(Mode::Firmware),
            Some(Mode::Restore) if st.mode == Mode::Restore => Some(Mode::Restore),
            _ => None,
        };
        if let Some(mode) = form {
            let fw = mode == Mode::Firmware;
            p.label(38.0, 772.0, if fw { "FIRMWARE FILE" } else { "BACKUP TO RESTORE" }, None);
            let cb = board.r(38.0, 784.0, 170.0, 34.0);
            let (c, h) = clicked("choose", cb);
            key(&p, cb, false, h && active, !active);
            p.text(123.0, 801.0, Align2::CENTER_CENTER, if fw { "CHOOSE FILE" } else { "CHOOSE BACKUP" }, "cond", 15.0, KEYINK, 1.0);
            if c && active {
                self.ctl.choose_file(if fw { FileKind::Firmware } else { FileKind::Restore });
            }
            let field = board.r(222.0, 784.0, 669.0, 34.0);
            p.pill(field, fw::SURFACE_CARD, 8.0, 2.5, 0.0);
            let value = if fw {
                match (&st.library_choice, st.file(FileKind::Firmware)) {
                    (Some(n), Some(_)) => Some(format!("{n} · from the library · SHA-256 OK")),
                    (_, f) => f,
                }
            } else {
                st.file(FileKind::Restore).map(|n| match st.backups.iter().find(|b| b.name == n) {
                    Some(b) => format!("{n} · {} · {}", b.time, b.firmware),
                    None => n,
                })
            };
            let (txt, col) = match value { Some(v) => (v, INK), None => ("no file chosen".into(), fw::INK_PLACEHOLDER) };
            p.text_fit(236.0, 801.0, &txt, "plex", 15.0, 640.0, col, 0.0);
            // the write's review, OR the other source
            let n = st.plan.as_ref().map(|p| p.count).unwrap_or(0);
            let ready = st.plan.as_ref().is_some_and(|p| p.ready) && st.phase != Phase::Done;
            let verb = if fw { "FLASH FIRMWARE" } else { "RESTORE BACKUP" };
            let label = match st.phase {
                Phase::Working => "WRITING…".to_string(),
                _ if ready => format!("{verb} · REVIEW {n} SECTORS"),
                _ if st.plan.as_ref().is_some_and(|p| p.kind == PlanKind::Write && p.count == 0) => "NOTHING TO WRITE".into(),
                _ => verb.into(),
            };
            // restore has one source, CHOOSE BACKUP: its review key takes the whole row
            let pr = if fw { board.r(38.0, 828.0, 480.0, 38.0) } else { board.r(38.0, 828.0, 853.0, 38.0) };
            let (pc, ph) = clicked("primary", pr);
            key(&p, pr, ready, ph && active && ready, !(active && ready));
            p.text(pr.center().x, 847.0, Align2::CENTER_CENTER, &label, "cond", 16.0, KEYINK, 1.0);
            if pc && active && ready {
                if fw && st.boot_first.is_some() {
                    self.dialog = Dialog::TwoStep;
                } else {
                    self.dialog = Dialog::Review;
                }
            } else if pc && !modal {
                self.nudged = now;
            }
            if fw {
                p.text(545.0, 847.0, Align2::CENTER_CENTER, "OR", "tech", 14.0, DIM, 1.6);
                let sr = board.r(572.0, 828.0, 319.0, 38.0);
                let (sc, sh) = clicked("othersource", sr);
                key(&p, sr, false, sh && active, !active);
                p.text(731.5, 847.0, Align2::CENTER_CENTER, "INSTALL FROM LIBRARY", "cond", 16.0, KEYINK, 1.0);
                if sc && active {
                    self.tab = Tab::Library;
                    self.ctl.load_library();
                }
            }
            if fw {
                let auto_line = match &st.backup_saved {
                    Some(n) => format!("AUTO BACKUP SAVED · {n}"),
                    None => "AUTO BACKUP BEFORE EVERY WRITE · THE BOOTLOADER IS KEPT".into(),
                };
                p.text(38.0, 880.0, Align2::LEFT_CENTER, &auto_line, "tech", 12.0, DIM, 1.6);
            } else if checkbox(ui, &p, 38.0, 880.0, "Keep this unit's bootloader (don't restore the boot area)", st.keep_boot, active) {
                self.ctl.set_keep_boot(!st.keep_boot);
            }
        }
        }

        // ---- transfer progress ----
        // FirmWave Stage pills (Figma Option A): white waiting, cyan now, green with a check when done
        p.label(38.0, 902.0, "TRANSFER PROGRESS", None);
        let mut x = 38.0;
        for (i, name) in ["READ", "BACKUP", "WRITE", "VERIFY"].iter().enumerate() {
            let done = st.stage[i] == Stage::Done;
            let w = p.measure(name, "cond", 13.0, 1.0) + 26.0 + if done { 16.0 } else { 0.0 };
            let fill = match st.stage[i] { Stage::Done => fw::STATUS_OK, Stage::Active => fw::CYAN, Stage::Idle => fw::SURFACE_CARD };
            p.pill(board.r(x, 918.0, w, 28.0), fill, 14.0, 2.5, if st.stage[i] == Stage::Active { 3.0 } else { 0.0 });
            if done {
                painter.add(Shape::line(vec![board.p(x + 12.0, 932.0), board.p(x + 16.0, 936.0), board.p(x + 23.0, 927.0)], Stroke::new(2.5 * s, INK)));
            }
            p.text(x + 13.0 + if done { 16.0 } else { 0.0 }, 932.0, Align2::LEFT_CENTER, name, "cond", 13.0, INK, 1.0);
            x += w + 8.0;
        }
        let bar = board.r(38.0, 962.0, 380.0, 18.0);
        p.pill(bar, fw::SURFACE_CARD, 9.0, 2.5, 0.0);
        let pct = st.pct.clamp(0.0, 1.0);
        if pct > 0.0 {
            p.pill(board.r(38.0, 962.0, (380.0 * pct).max(18.0), 18.0), fw::STATUS_OK, 9.0, 2.5, 0.0);
        }
        p.text(470.0, 971.0, Align2::RIGHT_CENTER, &format!("{}%", (st.pct * 100.0) as u32), "tech", 16.0, INK, 0.0);

        // ---- log ----
        p.label(496.0, 902.0, "OPERATION LOG", None);
        p.text(891.0, 902.0, Align2::RIGHT_CENTER, "CLEAR", "tech", 12.0, DIM, 1.5);
        if clicked("clearlog", board.r(850.0, 892.0, 42.0, 20.0)).0 && !modal {
            self.ctl.clear_log();
        }
        // FirmWave log (Figma DS · Log): a light card, black text in Space Mono
        p.card(board.r(496.0, 916.0, 395.0, 92.0), fw::BG_LOG, 10.0, 4.0);
        let clip = painter.with_clip_rect(board.r(506.0, 916.0, 378.0, 92.0));
        let lines: Vec<&String> = st.log.iter().rev().take(4).collect();
        for (i, l) in lines.iter().rev().enumerate() {
            let col = if l.contains("Refused") || l.contains("error") || l.contains("gone") { WARN } else { INK };
            p.note("plex", l);
            clip.text(board.p(508.0, 934.0 + i as f32 * 19.0), Align2::LEFT_CENTER, l.as_str(), board.f("plex", 13.0), col);
        }

        } else {
            self.library_view(ui, &p, &st, active);
        }

        // ---- flash map ----
        self.flash_map(ui, &p, &st);
        self.safety_check(ui, &p, &st, active);

        // ---- dialogs ----
        self.dialogs(ui, &p, &st, &line);

        let mut fonts = fonts;
        let missing = missing.into_inner();
        if !missing.is_empty() && fonts.search(&missing) {
            fonts.install(&ctx);
            ctx.request_repaint();
        }
        self.fonts = fonts;
    }
}

impl App {
    /// The Library tab: The Firmware Hub's list and the official packages,
    /// below the LCD. INSTALL checks the unit, plans the package and opens its
    /// review over this tab: WRITE there writes it as any firmware.
    fn library_view(&mut self, ui: &egui::Ui, p: &Painter, st: &Shared, active: bool) {
        let board = p.b;
        let clicked = |id: &str, r: Rect| -> (bool, bool) {
            let resp = ui.interact(r, ui.id().with(id), Sense::click());
            (resp.clicked(), resp.hovered())
        };
        let dev = st.device.as_ref();
        // "old" or "new" from the flash chip's text, "0x856014 (old)"
        let chip = dev.and_then(|d| if d.flash.contains("(new)") { Some("new") } else if d.flash.contains("(old)") { Some("old") } else { None });
        p.label(38.0, 388.0, "FIRMWARE LIBRARY", Some(891.0));
        // the search: typed text filters the list at once
        // FirmWave Search field (Figma L1): white, a black outline, rounded
        let field = board.r(38.0, 402.0, 296.0, 34.0);
        p.pill(field, fw::SURFACE_CARD, 8.0, if self.lib_query.is_empty() { 2.5 } else { 3.5 }, 0.0);
        draw_search(p, 56.0, 419.0, INK);
        let edit = egui::TextEdit::singleline(&mut self.lib_query)
            .id(ui.id().with("lib-search"))
            .frame(egui::Frame::NONE)
            .font(board.f("body", 14.0))
            .text_color(INK)
            .hint_text(egui::RichText::new("Search name, author, kind").color(fw::INK_PLACEHOLDER).font(board.f("body", 14.0)))
            .desired_width(field.width() - 70.0 * board.s);
        // a child Ui of its own on the board's layer: the view only holds a shared reference
        let mut field_ui = egui::Ui::new(ui.ctx().clone(), ui.id().with("lib-search-ui"),
            egui::UiBuilder::new().max_rect(board.r(70.0, 406.0, 232.0, 26.0)).layer_id(ui.layer_id()));
        let resp = field_ui.add(edit);
        // the favourites filter: a star with no key around it, filled when on
        let fr = board.r(342.0, 405.0, 30.0, 28.0);
        let (fc, fh) = clicked("lib-favorites", fr);
        draw_star(p, 357.0, 419.0, 11.0, self.lib_favorites, if self.lib_favorites { WARN } else if fh { DIM } else { INK });
        if fc && active {
            self.lib_favorites = !self.lib_favorites;
            self.lib_first = 0;
        }
        if resp.changed() {
            self.lib_first = 0;
        }
        if !self.lib_query.is_empty() {
            let x = board.r(308.0, 407.0, 24.0, 24.0);
            let (xc, xh) = clicked("lib-search-clear", x);
            p.text(320.0, 419.0, Align2::CENTER_CENTER, "×", "tech", 20.0, if xh { INK } else { DIM }, 0.0);
            if xc {
                self.lib_query.clear();
                self.lib_first = 0;
            }
        }
        if checkbox(ui, p, 384.0, 419.0, "This unit only", self.lib_compatible, active) {
            self.lib_compatible = !self.lib_compatible;
            self.lib_first = 0;
        }
        if checkbox(ui, p, 572.0, 419.0, "Betas", self.lib_beta, active) {
            self.lib_beta = !self.lib_beta;
            self.lib_first = 0;
        }
        let refresh = board.r(774.0, 405.0, 100.0, 28.0);
        let (rc, rh) = clicked("lib-refresh", refresh);
        key(p, refresh, true, rh && active, !active || st.library_loading);
        p.text(824.0, 419.0, Align2::CENTER_CENTER, "RELOAD", "cond", 15.0, KEYINK, 1.2);
        if rc && active && !st.library_loading {
            self.ctl.load_library();
        }
        let fits = |i: &crate::controller::LibItem| !(chip == Some("new") && i.chip == "old");
        let searching = !self.lib_query.trim().is_empty();
        let matches = |i: &crate::controller::LibItem| {
            crate::library::matches_search(&self.lib_query, &[&i.name, &i.id, &i.byline, &i.kinds, &i.version])
        };
        // what the search finds before the filters: what they hide is said, never silently lost
        let found = st.library.iter().filter(|i| matches(i)).count();
        let shown: Vec<&crate::controller::LibItem> = st.library.iter()
            .filter(|i| matches(i))
            .filter(|i| !self.lib_favorites || i.favorite)
            .filter(|i| self.lib_beta || !i.beta)
            .filter(|i| !self.lib_compatible || (fits(i) && !i.elsewhere))
            .collect();
        // scrolling over the list moves it a row at a time
        let list = board.r(38.0, LIB_TOP, 853.0, LIB_ROWS as f32 * LIB_STEP);
        if ui.rect_contains_pointer(list) && !shown.is_empty() {
            self.lib_scroll -= ui.input(|i| i.smooth_scroll_delta.y) / (LIB_STEP * board.s);
            let max = shown.len().saturating_sub(LIB_ROWS);
            while self.lib_scroll >= 1.0 { self.lib_scroll -= 1.0; self.lib_first = (self.lib_first + 1).min(max); }
            while self.lib_scroll <= -1.0 { self.lib_scroll += 1.0; self.lib_first = self.lib_first.saturating_sub(1); }
        }
        self.lib_first = self.lib_first.min(shown.len().saturating_sub(LIB_ROWS));
        if st.library_loading && st.library.is_empty() {
            p.text(56.0, 490.0, Align2::LEFT_CENTER, "READING THE FIRMWARE LIST…", "tech", 16.0, LAV, 2.0);
        } else if shown.is_empty() {
            let why = if self.lib_favorites && !searching {
                "No favourites yet: star a firmware with ☆ to find it here.".to_string()
            } else if !searching { "No firmwares to show: RELOAD, or change the filters above.".to_string() }
                else { format!("Nothing matches \"{}\"{}.", self.lib_query.trim(),
                               if self.lib_compatible || !self.lib_beta { " with these filters: try without them" } else { "" }) };
            p.text(56.0, 490.0, Align2::LEFT_CENTER, &why, "tech", 15.0, DIM, 0.3);
        }
        for (k, item) in shown.iter().skip(self.lib_first).take(LIB_ROWS).enumerate() {
            let y = LIB_TOP + k as f32 * LIB_STEP;
            let row = board.r(38.0, y, 853.0, 52.0);
            // INSTALL installs what is on this computer: GET (or UPDATE) downloads first
            // with no unit in download mode, INSTALL switches it first
            let installable = active && item.saved && !item.elsewhere && fits(item);
            let getting = st.library_getting.contains(&item.id);
            // not on this computer yet: GET downloads it alone, no unit needed (installing later, offline too);
            // an older version is: UPDATE
            let update = !item.saved && !item.older.is_empty();
            let gettable = !item.saved && !item.elsewhere && active && !getting;
            let deletable = item.saved || !item.older.is_empty();
            let br = board.r(728.0, y + 8.0, 140.0, 34.0);
            let (bc, bh) = clicked(&format!("lib-{}", item.id), br);
            let gr = board.r(632.0, y + 8.0, 86.0, 34.0);
            let (gc, gh) = if !item.saved && !item.elsewhere { clicked(&format!("lib-get-{}", item.id), gr) } else { (false, false) };
            let tr = board.r(if item.saved { 674.0 } else { 584.0 }, y + 8.0, 44.0, 34.0);
            let (tc, th) = if deletable { clicked(&format!("lib-del-{}", item.id), tr) } else { (false, false) };
            // FirmWave Library row (Figma L1): a white card, light green when saved on this computer
            let _ = (bh, gh, th);
            p.card(row, if item.saved { fw::SURFACE_SAVED } else { fw::SURFACE_CARD }, 10.0, 4.0);
            // the star: no key around it, filled when starred
            let sr = board.r(44.0, y + 12.0, 28.0, 28.0);
            let (sc, sh) = clicked(&format!("lib-star-{}", item.id), sr);
            draw_star(p, 60.0, y + 26.0, 10.0, item.favorite, if item.favorite { WARN } else if sh { DIM } else { INK });
            if sc && active {
                self.ctl.library_favorite(item.id.clone(), !item.favorite);
            }
            let nw = p.text_fit(82.0, y + 17.0, &item.name.to_uppercase(), "cond", 18.0, 400.0, INK, 0.6);
            p.text(82.0 + nw + 8.0, y + 18.0, Align2::LEFT_CENTER, &item.version, "plex", 13.0, DIM, 0.0);
            let mut tags = vec![item.kinds.to_uppercase()];
            if item.beta { tags.push("BETA".into()); }
            if item.saved { tags.push("SAVED · SHA-256 OK".into()); }
            if !fits(item) { tags.push("OLDER CHIP ONLY".into()); }
            if getting { tags.push("DOWNLOADING…".into()); }
            if update { tags.push(format!("{} SAVED · NEW {}", item.older.join(", "), item.version)); }
            let room = match (item.saved, item.elsewhere, deletable) { (true, _, _) => 600.0, (_, true, _) => 650.0, (_, _, true) => 520.0, _ => 560.0 };
            p.text_fit(82.0, y + 37.0, &format!("{} · {}", item.byline, tags.join(" · ")), "plex", 12.0, room - 28.0,
                       if fits(item) { DIM } else { BAD }, 0.0);
            let label = if item.elsewhere { "AUTHOR'S SITE" } else if !fits(item) { "NOT FOR THIS" } else { "INSTALL" };
            key(p, br, installable, bh && installable, !installable);
            p.text(798.0, y + 25.0, Align2::CENTER_CENTER, label, "cond", 15.0, KEYINK, 1.0);
            if !item.saved && !item.elsewhere {
                key(p, gr, false, gh && gettable, !gettable);
                p.text(675.0, y + 25.0, Align2::CENTER_CENTER, if getting { "…" } else if update { "UPDATE" } else { "GET" }, "cond", 15.0, KEYINK, 1.0);
                if gc && gettable {
                    self.ctl.library_get(item.id.clone());
                }
            }
            if deletable {
                key(p, tr, false, th && active, !active);
                draw_trash(p, (tr.center().x - board.o.x) / board.s, y + 25.0, KEYINK);
                if tc && active {
                    let versions = if item.saved { std::iter::once(item.version.clone()).chain(item.older.iter().cloned()).collect::<Vec<_>>() }
                                   else { item.older.clone() };
                    self.dialog = Dialog::Delete(item.id.clone(), item.name.clone(), versions.join(", "));
                }
            }
            if bc && installable {
                // checked, planned and reviewed from here: its review opens over this tab
                self.ctl.library_install(item.id.clone());
                self.op_open = Some(Mode::Firmware);
            } else if bc && !active {
                self.nudged = ui.input(|i| i.time);
            }
        }
        let count = if shown.len() > LIB_ROWS {
            format!("{}–{} OF {}", self.lib_first + 1, (self.lib_first + LIB_ROWS).min(shown.len()), shown.len())
        } else {
            format!("{} FOUND", shown.len())
        };
        p.text(760.0, 419.0, Align2::RIGHT_CENTER, &count, "plex", 13.0, INK, 0.0);
        let hidden = found - shown.len();
        if hidden > 0 {
            // an orange pill (Figma L1): what the filters hide is said, never silently lost
            let t = format!("{hidden} MORE HIDDEN BY {}", match (self.lib_favorites, self.lib_compatible, self.lib_beta) {
                (true, false, true) => "FAVOURITES", (false, true, true) => "THIS UNIT ONLY",
                (false, false, false) => "BETAS OFF", _ => "THE FILTERS" });
            let w = p.measure(&t, "tech", 11.0, 0.6) + 20.0;
            p.pill(board.r(891.0 - w, 443.0, w, 20.0), fw::STATUS_WARN, 10.0, 2.0, 0.0);
            p.text(891.0 - w / 2.0, 453.0, Align2::CENTER_CENTER, &t, "tech", 11.0, INK, 0.6);
        }
        let note = if dev.is_none() { "GET downloads for later. INSTALL checks the FM-1 first, then opens the review." }
                   else { "INSTALL opens the firmware's review: nothing is written before WRITE." };
        p.text_fit(38.0, 978.0, &format!("{note}  {}", st.library_note), "body", 13.0, 853.0, DIM, 0.0);
        p.text(38.0, 998.0, Align2::LEFT_CENTER, crate::library::ATTRIBUTION, "body", 13.0, DIM, 0.0);
    }

    /// The unit's safety at a glance (REQ-BOOT-01 first): each check passed or
    /// in need of attention, with the action that fixes it where there is one.
    fn safety_check(&mut self, ui: &egui::Ui, p: &Painter, st: &Shared, active: bool) {
        let board = p.b;
        let Some(d) = st.device.as_ref() else { return };
        let smk = d.model.contains("SMK");
        let (rec, menu) = if smk { ("SEQ REC", "SEQ PLAY") } else { ("REC", "PLAY") };
        let open = d.bootloader.starts_with("m-bootloader");
        let stock = d.bootloader.starts_with("stock");
        let tested = d.flash.contains("(old)") || d.flash.contains("(new)") || smk;
        // (name, passed, what it says, action label, action)
        let checks: [(&str, bool, String, Option<(&str, u8)>); 4] = [
            ("RECOVERY", open,
             if open { format!("m-bootloader · {rec}: download mode · {menu}: menu") }
             else if stock { "stock bootloader: no recovery from the keys".into() }
             else { format!("bootloader {}", d.bootloader) },
             if stock { Some(("INSTALL", 1)) } else { None }),
            ("BACKUP", st.backup_saved.is_some(),
             match &st.backup_saved { Some(n) => format!("saved this session · {n}"), None => "none of this session yet".into() },
             if st.backup_saved.is_none() { Some(("SAVE", 2)) } else { None }),
            ("FIRMWARE", d.app_ok,
             if d.app_ok { format!("{} · checks OK", d.firmware_shown()) } else { format!("{}: reinstall it from LIBRARY", d.app) },
             None),
            ("CHIP", d.chip_key_ok && tested,
             format!("chip key {}{} · flash {}{}", d.chip_key, if d.chip_key_ok { "" } else { " damaged" },
                     d.flash, if tested { "" } else { ": untested with m-bootloader" }),
             None),
        ];
        let need = checks.iter().filter(|c| !c.1).count();
        // FirmWave Safety check (Figma Option A): a white card; each check a green box with a tick, or an
        // orange box with "!" and the yellow key that fixes it
        p.label(938.0, 836.0, "SAFETY CHECK", None);
        let t = if need == 0 { "ALL CLEAR".to_string() } else { format!("{need} NEED ATTENTION") };
        let tw = p.measure(&t, "tech", 11.0, 0.6) + 22.0;
        p.pill(board.r(1500.0 - tw, 826.0, tw, 20.0), if need == 0 { fw::STATUS_OK } else { fw::STATUS_WARN }, 10.0, 2.0, 0.0);
        p.text(1500.0 - tw / 2.0, 836.0, Align2::CENTER_CENTER, &t, "tech", 11.0, INK, 0.6);
        p.card(board.r(938.0, 852.0, 562.0, 160.0), fw::SURFACE_CARD, 12.0, 5.0);
        for (k, (name, ok, text, action)) in checks.iter().enumerate() {
            let y = 872.0 + k as f32 * 36.0;
            if *ok { ok_box(p, 954.0, y - 11.0, 22.0) } else { alert_box(p, 954.0, y - 11.0, 22.0) }
            p.text(988.0, y - 5.0, Align2::LEFT_CENTER, name, "tech", 13.0, INK, 1.0);
            let room = if action.is_some() { 380.0 } else { 490.0 };
            p.text_fit(988.0, y + 10.0, text, "plex", 11.5, room, if *ok { DIM } else { INK }, 0.0);
            if let Some((label, code)) = action {
                let r = board.r(1400.0, y - 12.0, 84.0, 24.0);
                let resp = ui.interact(r, ui.id().with(("safety", *code)), Sense::click());
                let enabled = active && st.phase != Phase::Working;
                key(p, r, true, resp.hovered() && enabled, !enabled);
                p.text(1442.0, y, Align2::CENTER_CENTER, label, "cond", 12.0, KEYINK, 0.8);
                if resp.clicked() && enabled {
                    match code {
                        // install m-bootloader: reviewed as any write once planned
                        1 => self.review_when_planned = self.ctl.answer_boot_suggestion(true),
                        _ => self.ctl.save_backup(),
                    }
                }
            }
        }
    }

    fn flash_map(&mut self, ui: &egui::Ui, p: &Painter, st: &Shared) {
        let (board, s, painter) = (p.b, p.b.s, p.p);
        let dev = st.device.as_ref();
        p.label(938.0, 148.0, "FLASH MAP", Some(1310.0));
        let hovered_now = st.device.is_some() && ui.rect_contains_pointer(board.r(938.0, 170.0, 562.0, 570.0));
        if !hovered_now {
            p.text(1500.0, 148.0, Align2::RIGHT_CENTER, "1 MiB = 256 × 4 KiB", "tech", 15.0, LAV, 1.0);
        }
        // FirmWave Flash map (Figma Option A): the grid in a white card, each sector outlined in black
        p.card(board.r(938.0, 164.0, 562.0, 520.0), fw::SURFACE_CARD, 12.0, 5.0);
        let (gx, gy, gw, gh, pad, gap) = (976.0, 196.0, 508.0, 474.0, 0.0, 5.0);
        let cw = (gw - 2.0 * pad - 15.0 * gap) / 16.0;
        let ch = (gh - 2.0 * pad - 15.0 * gap) / 16.0;
        for c in 0..16 {
            p.text(gx + pad + c as f32 * (cw + gap) + cw / 2.0, 183.0, Align2::CENTER_CENTER, &format!("{c:X}"), "plex", 12.0, INK, 0.0);
        }
        let writing = st.phase == Phase::Working && st.mode != Mode::Backup;
        let dimmed = !st.marks.is_empty();
        let mut hovered = None;
        for i in 0..256 {
            let (row, col) = (i / 16, i % 16);
            if col == 0 {
                p.text(950.0, gy + pad + row as f32 * (ch + gap) + ch / 2.0, Align2::LEFT_CENTER, &format!("{:02X}", row * 16), "plex", 12.0, INK, 0.0);
            }
            let r = board.r(gx + pad + col as f32 * (cw + gap), gy + pad + row as f32 * (ch + gap), cw, ch);
            if ui.rect_contains_pointer(r) {
                hovered = Some(i);
            }
            let region = st.map[i];
            let mut mark = st.marks.get(&i).copied();
            let fade = if dev.is_none() { 0.38 } else if dimmed && mark.is_none() { 0.42 } else { 1.0 };
            let mut outline = None;
            let mut fill = region_color(region);
            let mut solid = region != Region::Free;
            if writing && mark == Some(Mark::Change) {
                mark = None;
                fill = hex(0x26302d);
                outline = Some(hex(0x4f6a62));
                solid = true;
            }
            match mark {
                Some(Mark::Change) => outline = Some(hex(0xf4efe4)),
                Some(Mark::Now) => {
                    fill = NOW;
                    solid = true;
                    painter.rect_filled(r.expand(4.0 * s), CornerRadius::same(3), NOW.gamma_multiply(0.25));
                }
                Some(Mark::Written) => {
                    fill = hex(0xb7fff0);
                    solid = true;
                }
                Some(Mark::Verified) => {
                    fill = hex(0x8ff5df);
                    outline = Some(hex(0x2c8f78));
                    solid = true;
                }
                None => {}
            }
            if solid && dev.is_some() {
                let cr = CornerRadius::same((3.0 * s).round() as u8);
                p.p.rect_filled(r, cr, fill.gamma_multiply(fade));
                p.p.rect_stroke(r, cr, Stroke::new(2.0 * s, INK.gamma_multiply(fade.max(0.5))), StrokeKind::Inside);
            } else if dev.is_some() && region == Region::Free {
                // a free sector: white, a dashed outline
                p.p.rect_filled(r, CornerRadius::same((3.0 * s).round() as u8), fw::MAP_FREE);
                dashed(p, r, INK.gamma_multiply(fade.max(0.5)));
            } else {
                hatch(p, r, fade);
            }
            if region == Region::Calibration && mark.is_none() {
                lock(p, r);
            }
            if let Some(c) = outline {
                painter.rect_stroke(r.expand(1.5 * s), CornerRadius::ZERO, Stroke::new(2.0 * s, c), StrokeKind::Outside);
            }
            if hovered == Some(i) && dev.is_some() {
                painter.rect_stroke(r.expand(2.0 * s), CornerRadius::ZERO, Stroke::new(2.0 * s, LAV), StrokeKind::Outside);
            }
        }

        // legend (Figma Option A): a pill per region this unit has, in its colour, with its sectors
        let (mut x, mut y) = (938.0, 712.0);
        for reg in Region::LEGEND {
            let ranges = region_ranges(&st.map, reg);
            if dev.is_some() && ranges.is_empty() {
                continue;
            }
            let t = if dev.is_some() { format!("{} {ranges}", reg.name().to_uppercase()) } else { reg.name().to_uppercase() };
            let w = p.measure(&t, "tech", 11.5, 0.5) + 22.0;
            if x + w > 1500.0 {
                x = 938.0;
                y += 30.0;
            }
            let r = board.r(x, y - 11.0, w, 22.0);
            if reg == Region::Free {
                p.p.rect_filled(r, CornerRadius::same((11.0 * s) as u8), fw::MAP_FREE);
                dashed(p, r, INK);
            } else {
                p.pill(r, region_color(reg), 11.0, 2.0, 0.0);
            }
            p.text(x + w / 2.0, y, Align2::CENTER_CENTER, &t, "tech", 11.5, INK, 0.5);
            x += w + 8.0;
        }
        let n = st.marks.len();
        let marks: Vec<(Color32, Option<Color32>, String)> = if writing {
            vec![(hex(0xb7fff0), None, "WRITTEN".into()), (NOW, None, "NOW".into()),
                 (hex(0x26302d), Some(hex(0x4f6a62)), "PENDING".into()), (hex(0x8ff5df), Some(hex(0x2c8f78)), "VERIFIED".into())]
        } else if st.phase == Phase::Done && n > 0 {
            vec![(hex(0x8ff5df), Some(hex(0x2c8f78)), format!("WRITTEN AND VERIFIED · {n}"))]
        } else if n > 0 {
            vec![(Color32::TRANSPARENT, Some(hex(0xf4efe4)), format!("WILL CHANGE · {n}")), (hex(0x3b4a45), None, "UNTOUCHED".into())]
        } else {
            vec![]
        };
        // what the map marks, in a white box (Figma Option A)
        if dev.is_some() {
            p.card(board.r(938.0, 778.0, 562.0, 34.0), fw::SURFACE_CARD, 8.0, 4.0);
        }
        let mut x = 954.0;
        for (fill, ol, t) in &marks {
            let sq = board.r(x, 786.0, 18.0, 18.0);
            p.rect(sq, *fill);
            if let Some(c) = ol { p.frame(sq, *c, 2.0) }
            let w = p.text(x + 26.0, 795.0, Align2::LEFT_CENTER, t, "tech", 13.0, INK, 0.8);
            x += 26.0 + w + 28.0;
        }
        if marks.is_empty() && dev.is_some() {
            let t = if st.mode == Mode::Backup { "A backup reads all 256 sectors" } else { "Nothing marked: choose a firmware to see what changes" };
            p.text(954.0, 795.0, Align2::LEFT_CENTER, t, "cond", 14.0, INK, 0.3);
        }
        if let (Some(i), Some(_)) = (hovered, dev) {
            let what = match st.marks.get(&i) {
                Some(Mark::Change) => " · will change",
                Some(Mark::Written) => " · written",
                Some(Mark::Verified) => " · written and verified",
                Some(Mark::Now) => " · writing now",
                None => "",
            };
            // in the map's heading: the space below is the safety check's
            p.text(1500.0, 148.0, Align2::RIGHT_CENTER,
                &format!("SECTOR {:02X} · 0x{:05X}-0x{:05X} · {}{what}", i, i * 0x1000, i * 0x1000 + 0xFFF, st.map[i].name()),
                "plex", 14.0, INK, 0.0);
        }
    }

    fn dialogs(&mut self, ui: &egui::Ui, p: &Painter, st: &Shared, line: &str) {
        let (board, painter) = (p.b, p.p);
        let ctl = self.ctl.clone();
        // the dialogs' own titles and keys (Figma: Notificações, side by side)
        let chip = st.device.as_ref().map(|d| d.flash_label.split(" · ").next().unwrap_or("").to_string()).unwrap_or_default();
        let alert_title = match &st.alert {
            Some((_, t)) if t.contains("is now protected with m-bootloader") => format!("m-bootloader {} INSTALLED", st.open_version),
            Some((_, t)) if t.ends_with("Restarting the FM-1…") => "RESTARTING FM-1".into(),
            Some((_, t)) if t.contains("didn't show up on USB within 60 seconds") => "CHECK FM-1 DISPLAY".into(),
            // Figma scenario 11: the firmware by the name it was chosen by
            Some((_, t)) if t.contains("restarted and is running") => match (st.mode, st.file(FileKind::Firmware)) {
                (Mode::Firmware, Some(f)) => format!("{} RUNNING", f.trim_end_matches(".fwsc").to_uppercase()),
                _ => "FIRMWARE RUNNING".into(),
            },
            Some((_, t)) if t.starts_with("Backup saved") => "Backup saved".into(),
            Some((_, t)) if t.contains("older flash chip") => format!("WARNING: NEW {chip} CHIP"),
            _ => String::new(),
        };
        let boot_key = format!("INSTALL M-BOOTLOADER {}", st.open_version);
        // a firmware's review: titled by what it installs, its plan in flash memory sectors on the LCD
        let fw_name = st.file(FileKind::Firmware).unwrap_or_default().trim_end_matches(".fwsc").to_uppercase();
        let fw_review = st.mode == Mode::Firmware;
        let restore_name = st.file(FileKind::Restore).unwrap_or_default();
        let restore_time = st.backups.iter().find(|b| b.name == restore_name).map(|b| b.time.clone());
        let review_title = match st.mode {
            Mode::Firmware => format!("Install {fw_name}?"),
            Mode::Restore => match &restore_time {
                Some(t) => format!("Restore the backup of {t}?"),
                None => format!("Restore {restore_name}?"),
            },
            _ => "REVIEW".into(),
        };
        let review_line = match &st.plan {
            Some(p) if fw_review => format!("INSTALL {fw_name} · {} FLASH MEMORY SECTORS", p.count),
            Some(p) if st.mode == Mode::Restore => format!("RESTORE {} · {} FLASH MEMORY SECTORS", restore_name.to_uppercase(), p.count),
            _ => line.to_string(),
        };
        // the review's WRITE key says what it installs
        let write_key = match st.mode {
            Mode::Firmware => format!("INSTALL {}", st.file(FileKind::Firmware).unwrap_or_default().trim_end_matches(".fwsc").to_uppercase()),
            Mode::Bootloader => boot_key.clone(),
            Mode::Restore => "RESTORE BACKUP".into(),
            _ => format!("WRITE {} SECTORS", st.plan.as_ref().map(|p| p.count).unwrap_or(0)),
        };
        // an alert from the controller wins over the user's dialogs
        let (title, body, keys): (&str, Vec<(String, Color32)>, Vec<(&str, u8)>) = if let Some((kind, text)) = &st.alert {
            let (t, c) = match kind {
                _ if !alert_title.is_empty() => (alert_title.as_str(), if *kind == AlertKind::Done { MINT } else { BAD }),
                AlertKind::Done => ("DONE", MINT),
                AlertKind::Refused => ("REFUSED", BAD),
                AlertKind::Error => ("ERROR", BAD),
            };
            let mut t2 = text.clone();
            if let Some(f) = t2.get_mut(0..1) { f.make_ascii_uppercase(); }
            (t, t2.split('\n').map(|l| (l.to_string(), c)).collect(), vec![("OK", 1)])
        } else if let Some(text) = &st.how_to {
            ("Manual Action: put FM-1 in Download Mode", text.split('\n').map(|l| (l.to_string(), INK)).collect(), vec![("CANCEL", 12)])
        } else if let (Some(menu_key), Dialog::None) = (st.suggest_boot, &self.dialog) {
            ("INSTALL M-BOOTLOADER?", vec![
                ("This unit has M-VAVE's stock bootloader. We recommend installing m-bootloader: if a firmware ever \
                  fails to start, you can still reinstall one without buying extra hardware to force download mode.".into(), INK),
                (format!("For its recovery menu, hold {menu_key} while switching the unit on."), INK),
                ("Only the bootloader is replaced. You review the change before anything is written, and a full \
                  backup is saved first.".into(), DIM),
            ], vec![("NO", 6), ("YES", 5)])
        } else {
            match self.dialog {
                Dialog::None => return,
                Dialog::Review => {
                    let Some(plan) = st.plan.as_ref().filter(|p| p.ready && st.phase == Phase::Ready) else {
                        self.dialog = Dialog::None;
                        return;
                    };
                    let mut b = vec![(format!("{}: writes {} sectors ({} KiB): {}", plan.title, plan.count, plan.count * 4, plan.ranges), INK)];
                    b.extend(plan.notes.iter().filter(|n| !n.starts_with("Installs ")).map(|n| (n.clone(), INK)));
                    b.extend(plan.warnings.iter().map(|w| (format!("⚠ {w}"), WARN)));
                    b.push((match &st.backup_saved {
                        Some(n) => format!("Backup of this session: {n}; everything written is read back and verified."),
                        None => "A full backup of the unit is saved first; everything written is read back and verified.".into(),
                    }, MINT));
                    b.push(("Do not unplug or switch off the unit while it writes.".into(), BAD));
                    (review_title.as_str(), b, vec![("CANCEL", 0), ("WRITE", 2)])
                }
                Dialog::TwoStep => {
                    if !st.plan.as_ref().is_some_and(|p| p.ready && st.phase == Phase::Ready) {
                        self.dialog = Dialog::None;
                        return;
                    }
                    let key = st.boot_first.unwrap_or("PLAY");
                    let fw = st.file(FileKind::Firmware).unwrap_or_default();
                    // Figma: scenario 5 (46:466)
                    let (unit, rec) = if key == "SEQ PLAY" { ("SMK-37", "SEQ REC") } else { ("FM-1", "REC") };
                    ("STOCK BOOTLOADER DETECTED: REPLACE IT WITH M-BOOTLOADER (2-STEPS)", vec![
                        (format!("This {unit} has M-VAVE's stock bootloader. If the new firmware does not start, only a \
                                  hardware accessory enables the {unit} to enter Download Mode. The recommendation is to \
                                  replace the stock bootloader with m-bootloader."), INK),
                        (String::new(), INK),
                        (format!("Step 1 · m-bootloader {} · the boot area only, 0x00000-0x03FFF (16 KiB).",
                                 st.open_version), MINT),
                        (format!("After that, it's easier to access Download Mode and Recovery Menu. Keep {rec} button \
                                  pressed for Download Mode or {key} button for Recovery Menu before switching on the \
                                  {unit}."), INK),
                        (String::new(), INK),
                        (format!("Step 2 · The app shows the {} confirmation box. It only installs the firmware with \
                                  your authorization.", fw.trim_end_matches(".fwsc")), MINT),
                        (String::new(), INK),
                        ("Each step is reviewed, backed up first and verified, as any write.".into(), DIM),
                    ], vec![("CANCEL", 0), ("SKIP M-BOOTLOADER", 10), (boot_key.as_str(), 11)])
                }
                Dialog::Delete(_, ref name, ref versions) => ("DELETE FROM THIS COMPUTER?", vec![
                    (format!("Deletes the downloaded {name} ({versions}) from this computer."), INK),
                    ("The unit is not touched. GET downloads it again, with its SHA-256 checked.".into(), DIM),
                ], vec![("CANCEL", 0), ("DELETE", 9)]),
                Dialog::About => ("ABOUT", vec![
                    (format!("FirmWave {}: back up, restore and reinstall the firmware of the M-VAVE FM-1 and SMK-37 \
                              Pro / Elite through the chip's USB download mode, and install m-bootloader.",
                             env!("CARGO_PKG_VERSION")), INK),
                    (String::new(), INK),
                    ("FirmWave by Wellington Saam: the firmware library, m-bootloader before a firmware \
                      (REQ-BOOT-01), the Device tab's checks and the Android app. Based on M-Flasher by Julius \
                      Vitkauskas (MIT).".into(), INK),
                    (String::new(), INK),
                    ("Bootloader and flash loader: m-bootloader (MIT). Protocol after jl-uboot-tool by \
                      kagaimiq (MIT). Fonts: Lexend Mega, Public Sans, Space Mono and IBM Plex Mono (OFL).".into(), DIM),
                    (String::new(), INK),
                    ("Unofficial: not affiliated with M-VAVE or JieLi, nor supported by M-Flasher's author.".into(), DIM),
                    (String::new(), INK),
                    ("FirmWave is free, maintained by Wellington Saam. Questions and bug reports go to its own \
                      GitHub repository, not to M-Flasher's.".into(), INK),
                ], vec![("GITHUB", 7), ("CLOSE", 0)]),
                Dialog::Model => {
                    let mut b = vec![
                        ("The model could not be identified for certain. Writing the wrong model's firmware or".to_string(), INK),
                        ("bootloader can make the unit unusable: choose only if you are sure. Found:".into(), WARN),
                    ];
                    let ev = st.device.as_ref().map(|d| d.evidence.clone()).unwrap_or_default();
                    b.extend(ev.into_iter().map(|e| (format!("· {e}"), DIM)));
                    ("WHICH MODEL IS THIS?", b, vec![("CANCEL", 0), ("FM-1", 3), ("SMK-37", 4)])
                }
            }
        };
        let screen = painter.clip_rect();
        painter.rect_filled(screen, CornerRadius::ZERO, Color32::from_black_alpha(110));
        // wrapped to the dialog's text width, measured in the font itself (Figma: Public Sans Medium)
        let fits = |l: &str| p.measure(l, "body", 17.0, 0.0) <= 690.0;
        let wrapped: Vec<(String, Color32)> = body.into_iter().flat_map(|(t, c)| wrap(&t, &fits).into_iter().map(move |l| (l, c))).collect();
        let review = self.dialog == Dialog::Review && st.alert.is_none();
        let h = 182.0 + wrapped.len() as f32 * 27.0 + if review { 66.0 } else { 0.0 };
        let top = (H - h) / 2.0;
        let d = board.r(388.0, top, 760.0, h);
        // FirmWave dialog (Figma 208:3349): a white card, 3 px black outline, hard shadow
        p.card(d, fw::SURFACE_CARD, 14.0, 8.0);
        // the title band (Figma: Faixa do título): cyan, green when done, orange when refused; black text
        let band_color = match &st.alert {
            Some((AlertKind::Done, _)) => fw::STATUS_OK,
            Some(_) => fw::STATUS_WARN,
            None => fw::CYAN,
        };
        let band = board.r(420.0, top + 30.0, 696.0, 40.0);
        p.p.rect_filled(band, CornerRadius::same((8.0 * board.s) as u8), band_color);
        p.p.rect_stroke(band, CornerRadius::same((8.0 * board.s) as u8), Stroke::new(2.0 * board.s, fw::BORDER_DEFAULT), StrokeKind::Inside);
        p.text(436.0, top + 50.0, Align2::LEFT_CENTER, &title.to_uppercase(), "label", 15.0, INK, 1.2);
        let mut y = top + 86.0;
        if review {
            let lcd = board.r(420.0, y, 696.0, 52.0);
            p.vgrad(lcd, LCD1, LCD2);
            p.text_fit(436.0, y + 26.0, &review_line, "lcd", 28.0, 664.0, LCDINK, 1.0);
            y += 66.0;
        }
        for (l, c) in &wrapped {
            p.text(420.0, y + 12.0, Align2::LEFT_CENTER, l, "body", 17.0, *c, 0.0);
            y += 27.0;
        }
        let mut x = 1116.0;
        for (label, code) in keys.iter().rev() {
            // the key we recommend: lit
            // (and CLOSE where it is the only way on: About, a finished or refused operation)
            let primary = matches!(code, 2 | 5 | 11) || (*code == 0 && (self.dialog == Dialog::About || st.alert.is_some()));
            let t = if *code == 2 { write_key.clone() } else { label.to_string() };
            let w = (p.measure(&t, "cond", 22.0, 1.6) + 48.0).max(if primary { 254.0 } else { 150.0 });
            x -= w;
            let r = board.r(x, top + h - 78.0, w, 52.0);
            let resp = ui.interact(r, ui.id().with(("dlg", *label)), Sense::click());
            key(p, r, primary, resp.hovered(), false);
            p.text(x + w / 2.0, top + h - 52.0, Align2::CENTER_CENTER, &t, "cond", 22.0, KEYINK, 1.6);
            if resp.clicked() {
                if st.alert.is_some() {
                    ctl.dismiss();
                } else if *code == 7 {
                    // a link: the dialog stays
                    ctl.open_link(REPO);
                } else {
                    match code {
                        2 => ctl.confirm(),
                        3 => ctl.set_model("fm1"),
                        4 => ctl.set_model("smk37"),
                        5 => self.review_when_planned = ctl.answer_boot_suggestion(true),
                        6 => {
                            ctl.answer_boot_suggestion(false);
                        }
                        9 => {
                            if let Dialog::Delete(id, _, _) = &self.dialog {
                                ctl.library_delete(id.clone());
                            }
                        }
                        12 => ctl.cancel_install_wait(),
                        // m-bootloader, then the firmware: reviewed as installing it (opens once planned)
                        11 => {
                            ctl.bootloader_then_firmware();
                            self.review_when_planned = true;
                        }
                        // step 1's review given up: the firmware stays chosen
                        0 if st.firmware_after => ctl.cancel_two_step(),
                        _ => {}
                    }
                    // SKIP M-BOOTLOADER: straight to the firmware's review
                    self.dialog = if *code == 10 { Dialog::Review } else { Dialog::None };
                }
            }
            x -= 16.0;
        }
        if ui.input(|i| i.key_pressed(egui::Key::Escape)) && st.alert.is_none() {
            if st.how_to.is_some() {
                ctl.cancel_install_wait();
            } else if self.dialog == Dialog::None && st.suggest_boot.is_some() {
                ctl.answer_boot_suggestion(false);
            }
            self.dialog = Dialog::None;
        }
    }
}

/// Lines of text, broken at spaces so that each one fits; an empty text is one
/// empty line.
fn wrap(text: &str, fits: &dyn Fn(&str) -> bool) -> Vec<String> {
    if text.is_empty() {
        return vec![String::new()];
    }
    let mut out = vec![];
    let mut line = String::new();
    for word in text.split(' ') {
        if !line.is_empty() && !fits(&format!("{line} {word}")) {
            out.push(std::mem::take(&mut line));
        }
        if !line.is_empty() {
            line.push(' ');
        }
        line.push_str(word);
    }
    if !line.is_empty() {
        out.push(line);
    }
    out
}

/// The window's icon: macOS's with the margin its icons have, the others' without.
const ICON: &[u8] = if cfg!(target_os = "macos") {
    include_bytes!("../assets/icon-256.png")
} else {
    include_bytes!("../linux/firmwave.png")
};

pub fn run() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("FirmWave")
            // Linux: matches the installed launcher, for the dock's icon
            .with_app_id("io.github.wellingtonsaam.firmwave")
            .with_inner_size(vec2(1280.0, 853.0))
            .with_min_inner_size(vec2(900.0, 600.0))
            // ours (macos/icon.svg), not egui's: the Dock and taskbars show it
            .with_icon(eframe::icon_data::from_png_bytes(ICON).unwrap_or_default()),
        #[cfg(windows)]
        wgpu_options: wgpu_options(),
        ..Default::default()
    };
    eframe::run_native("FirmWave", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}

/// Windows: Direct3D 12 (the only backend built in); on a laptop with two
/// GPUs, the integrated one, which drives the display (a panel needs no more);
/// and Windows' own shader compiler, so that no dxcompiler.dll is looked for.
/// The WGPU_* environment variables still override each.
#[cfg(windows)]
fn wgpu_options() -> eframe::egui_wgpu::WgpuConfiguration {
    use eframe::egui_wgpu::{WgpuConfiguration, WgpuSetup, WgpuSetupCreateNew};
    use eframe::wgpu;
    let mut setup = WgpuSetupCreateNew::without_display_handle();
    setup.instance_descriptor.backends = wgpu::Backends::from_env().unwrap_or(wgpu::Backends::DX12);
    setup.instance_descriptor.backend_options.dx12.shader_compiler =
        wgpu::Dx12Compiler::from_env().unwrap_or(wgpu::Dx12Compiler::Fxc);
    setup.power_preference = wgpu::PowerPreference::from_env().unwrap_or(wgpu::PowerPreference::LowPower);
    WgpuConfiguration { wgpu_setup: WgpuSetup::CreateNew(setup), ..Default::default() }
}

// ---- drawing helpers (board coordinates, text, keys, icons) ----

/// Board coordinates -> screen.
struct Board {
    s: f32,
    o: Pos2,
}

impl Board {
    fn p(&self, x: f32, y: f32) -> Pos2 {
        pos2(self.o.x + x * self.s, self.o.y + y * self.s)
    }
    fn r(&self, x: f32, y: f32, w: f32, h: f32) -> Rect {
        Rect::from_min_size(self.p(x, y), vec2(w * self.s, h * self.s))
    }
    fn f(&self, family: &str, size: f32) -> FontId {
        FontId::new(size * self.s, FontFamily::Name(family.into()))
    }
}

struct Painter<'a> {
    p: &'a egui::Painter,
    b: &'a Board,
    fonts: &'a crate::fonts::Fonts,
    missing: &'a RefCell<Vec<char>>,
}

impl Painter<'_> {
    /// Notes the letters of a text that the fonts lack.
    fn note(&self, family: &str, s: &str) {
        self.fonts.missing(family, s, &mut self.missing.borrow_mut());
    }
    fn rect(&self, r: Rect, fill: Color32) {
        self.p.rect_filled(r, CornerRadius::ZERO, fill);
    }
    /// FirmWave's card: a fill, a 3 px black outline, rounded corners and a hard black shadow (blur 0)
    fn card(&self, r: Rect, fill: Color32, radius: f32, shadow: f32) {
        self.pill(r, fill, radius, 3.0, shadow);
    }
    /// A card with an outline of its own width: pills, tags and fields
    fn pill(&self, r: Rect, fill: Color32, radius: f32, border: f32, shadow: f32) {
        let s = self.b.s;
        let cr = CornerRadius::same((radius * s).round().min(255.0) as u8);
        if shadow > 0.0 {
            self.p.rect_filled(r.translate(vec2(shadow * s, shadow * s)), cr, fw::SHADOW_DEFAULT);
        }
        self.p.rect_filled(r, cr, fill);
        if border > 0.0 {
            self.p.rect_stroke(r, cr, Stroke::new(border * s, fw::BORDER_DEFAULT), StrokeKind::Inside);
        }
    }
    fn frame(&self, r: Rect, color: Color32, width: f32) {
        self.p.rect_stroke(r, CornerRadius::ZERO, Stroke::new(width * self.b.s, color), StrokeKind::Inside);
    }
    fn vgrad(&self, r: Rect, top: Color32, bottom: Color32) {
        let mut m = Mesh::default();
        let i = m.vertices.len() as u32;
        m.colored_vertex(r.left_top(), top);
        m.colored_vertex(r.right_top(), top);
        m.colored_vertex(r.right_bottom(), bottom);
        m.colored_vertex(r.left_bottom(), bottom);
        m.add_triangle(i, i + 1, i + 2);
        m.add_triangle(i, i + 2, i + 3);
        self.p.add(Shape::mesh(m));
    }
    fn hline(&self, x0: f32, x1: f32, y: f32, color: Color32) {
        let b = self.b;
        self.p.line_segment([b.p(x0, y), b.p(x1, y)], Stroke::new(b.s.max(1.0), color));
    }
    fn vline(&self, x: f32, y0: f32, y1: f32, color: Color32) {
        let b = self.b;
        self.p.line_segment([b.p(x, y0), b.p(x, y1)], Stroke::new(b.s.max(1.0), color));
    }
    /// Text at board (x, y) with letter spacing; returns its width in board units.
    #[allow(clippy::too_many_arguments)]
    fn text(&self, x: f32, y: f32, anchor: Align2, s: &str, family: &str, size: f32, color: Color32, spacing: f32) -> f32 {
        let b = self.b;
        self.note(family, s);
        let mut job = egui::text::LayoutJob::default();
        job.append(
            s,
            0.0,
            egui::TextFormat { font_id: b.f(family, size), color, extra_letter_spacing: spacing * b.s, ..Default::default() },
        );
        let galley = self.p.layout_job(job);
        let size = galley.size();
        let rect = anchor.anchor_size(b.p(x, y), size);
        self.p.galley(rect.min, galley, color);
        size.x / b.s
    }
    /// Width of a text in board units.
    fn measure(&self, s: &str, family: &str, size: f32, spacing: f32) -> f32 {
        let b = self.b;
        let mut job = egui::text::LayoutJob::default();
        job.append(s, 0.0, egui::TextFormat { font_id: b.f(family, size), extra_letter_spacing: spacing * b.s, ..Default::default() });
        self.p.layout_job(job).size().x / b.s
    }
    /// Text left-aligned at (x, y), its size reduced (to 60% at most) to fit max_w.
    #[allow(clippy::too_many_arguments)]
    fn text_fit(&self, x: f32, y: f32, s: &str, family: &str, size: f32, max_w: f32, color: Color32, spacing: f32) -> f32 {
        let mut z = size;
        while z > size * 0.6 && self.measure(s, family, z, spacing) > max_w {
            z -= 1.0;
        }
        self.text(x, y, Align2::LEFT_CENTER, s, family, z, color, spacing)
    }
    /// A section label in lavender, optionally with a rule to `rule_to`.
    fn label(&self, x: f32, y: f32, s: &str, rule_to: Option<f32>) {
        let w = self.text(x, y, Align2::LEFT_CENTER, s, "label", 13.0, LAV, 1.2);
        if let Some(x1) = rule_to {
            self.hline(x + w + 14.0, x1, y, LAVLINE);
        }
    }
}


fn key(p: &Painter, r: Rect, on: bool, hover: bool, disabled: bool) {
    // FirmWave Key (Figma DS · Key): white, or yellow when it is the recommended next step; pressed on hover
    let fill = if disabled { fw::SURFACE_EMPTY } else if on { fw::ACCENT_PRIMARY } else { KEY_TOP };
    let lift = if hover && !disabled { 3.0 } else { 0.0 };
    let r = r.translate(vec2(lift, lift) * p.b.s);
    p.card(r, fill, 12.0, if disabled { 0.0 } else { 5.0 - lift });
}

/// A key of its own colour (the operations: SAVE cyan, RESTORE pink, INSTALL lilac)
fn key_fill(p: &Painter, r: Rect, fill: Color32, hover: bool, disabled: bool) {
    let lift = if hover && !disabled { 3.0 } else { 0.0 };
    let r = r.translate(vec2(lift, lift) * p.b.s);
    p.card(r, if disabled { fw::SURFACE_EMPTY } else { fill }, 12.0, if disabled { 0.0 } else { 5.0 - lift });
}

/// What needs attention: a black-outlined orange box with "!" (Figma DS · Status Icon · Warn)
fn alert_box(p: &Painter, x: f32, y: f32, size: f32) {
    p.pill(p.b.r(x, y, size, size), fw::STATUS_WARN, 4.0, 2.0, 0.0);
    p.text(x + size / 2.0, y + size / 2.0, Align2::CENTER_CENTER, "!", "cond", size * 0.7, INK, 0.0);
}

/// What passed: a black-outlined green box with a tick (Figma DS · Status Icon · OK)
fn ok_box(p: &Painter, x: f32, y: f32, size: f32) {
    let b = p.b;
    p.pill(b.r(x, y, size, size), fw::STATUS_OK, 4.0, 2.0, 0.0);
    let k = size / 22.0;
    p.p.add(Shape::line(vec![b.p(x + 6.0 * k, y + 11.5 * k), b.p(x + 9.5 * k, y + 15.0 * k), b.p(x + 16.0 * k, y + 7.0 * k)],
                        Stroke::new(2.5 * b.s, INK)));
}

/// A dashed black outline around r (a free sector)
fn dashed(p: &Painter, r: Rect, color: Color32) {
    let s = p.b.s;
    let r = r.shrink(0.75 * s);
    let pts = vec![r.left_top(), r.right_top(), r.right_bottom(), r.left_bottom(), r.left_top()];
    p.p.extend(Shape::dashed_line(&pts, Stroke::new(1.5 * s, color), 3.0 * s, 2.5 * s));
}

/// The app icon's drawing (Figma App Icon 308:102285, "onda com circuito"): the wave between two solder
/// pads, with a 45° trace from its trough to a third pad; in the icon's own 166-unit square at (x, y)
fn wave_circuit(p: &Painter, x: f32, y: f32, size: f32) {
    let b = p.b;
    let k = size / 166.0;
    let at = |u: f32, v: f32| b.p(x + u * k, y + v * k);
    let line = Stroke::new(6.0 * k * b.s, INK);
    p.p.line_segment([at(51.0, 73.5), at(35.5, 73.5)], line);
    p.p.line_segment([at(115.0, 73.5), at(130.5, 73.5)], line);
    p.p.add(Shape::line(vec![at(83.0, 85.5), at(83.0, 99.5), at(99.0, 115.5)], line));
    let wave: Vec<Pos2> = (0..=64).map(|i| {
        let u = 51.0 + 64.0 * i as f32 / 64.0;
        at(u, 73.5 - 17.33 * (std::f32::consts::PI * (u - 51.0) / 21.33).sin())
    }).collect();
    p.p.add(Shape::line(wave, Stroke::new(10.0 * k * b.s, INK)));
    for (u, v) in [(35.5, 73.5), (130.5, 73.5), (99.0, 115.5)] {
        p.p.circle_filled(at(u, v), 8.0 * k * b.s, fw::CORAL);
        p.p.circle_stroke(at(u, v), 8.0 * k * b.s, Stroke::new(5.0 * k * b.s, INK));
    }
}

fn led(p: &Painter, c: Pos2, on: bool) {
    // FirmWave LED (Figma DS · LED): green or dark gray, 2 px black ring
    let s = p.b.s;
    p.p.circle_filled(c, 7.0 * s, if on { fw::LED_ON } else { fw::LED_OFF });
    p.p.circle_stroke(c, 7.0 * s, Stroke::new(2.0 * s, fw::BORDER_DEFAULT));
}

fn checkbox(ui: &egui::Ui, p: &Painter, x: f32, y: f32, text: &str, on: bool, enabled: bool) -> bool {
    let b = p.b;
    let box_r = b.r(x, y - 10.0, 20.0, 20.0);
    p.p.rect_filled(box_r, CornerRadius::same((4.0 * b.s) as u8), if on { fw::STATUS_OK } else { fw::SURFACE_CARD });
    p.p.rect_stroke(box_r, CornerRadius::same((4.0 * b.s) as u8), Stroke::new(2.0 * b.s, fw::BORDER_DEFAULT), StrokeKind::Inside);
    if on {
        let pts = [b.p(x + 4.0, y), b.p(x + 8.5, y + 5.0), b.p(x + 16.0, y - 6.0)];
        p.p.line(pts.to_vec(), Stroke::new(3.0 * b.s, fw::INK_PRIMARY));
    }
    let w = p.text(x + 34.0, y, Align2::LEFT_CENTER, text, "tech", 16.0, INK, 0.4);
    let resp = ui.interact(b.r(x, y - 12.0, 34.0 + w, 24.0), ui.id().with(text), Sense::click());
    enabled && resp.clicked()
}

/// A five-pointed star of outer radius r, centred at (x, y): filled, or its outline.
fn draw_star(p: &Painter, x: f32, y: f32, r: f32, filled: bool, color: Color32) {
    let b = p.b;
    let pts: Vec<Pos2> = (0..10).map(|k| {
        let a = std::f32::consts::PI * (k as f32 / 5.0) - std::f32::consts::FRAC_PI_2;
        let rr = if k % 2 == 0 { r } else { r * 0.45 };
        b.p(x + rr * a.cos(), y + rr * a.sin())
    }).collect();
    if filled {
        // a fan from the centre: the star is star-shaped around it
        let mut m = Mesh::default();
        let c = m.vertices.len() as u32;
        m.colored_vertex(b.p(x, y), color);
        for q in &pts {
            m.colored_vertex(*q, color);
        }
        for k in 0..10u32 {
            m.add_triangle(c, c + 1 + k, c + 1 + (k + 1) % 10);
        }
        p.p.add(Shape::mesh(m));
    } else {
        p.p.add(Shape::closed_line(pts, Stroke::new(1.6 * b.s, color)));
    }
}

/// Material Design's "delete" (trash can) icon, outlined, centred at (x, y).
fn draw_trash(p: &Painter, x: f32, y: f32, color: Color32) {
    let b = p.b;
    let st = Stroke::new(1.8 * b.s, color);
    p.p.line_segment([b.p(x - 8.0, y - 6.0), b.p(x + 8.0, y - 6.0)], st);
    p.p.line_segment([b.p(x - 3.0, y - 6.0), b.p(x - 3.0, y - 9.0)], st);
    p.p.line_segment([b.p(x - 3.0, y - 9.0), b.p(x + 3.0, y - 9.0)], st);
    p.p.line_segment([b.p(x + 3.0, y - 9.0), b.p(x + 3.0, y - 6.0)], st);
    p.p.add(Shape::line(vec![b.p(x - 6.0, y - 6.0), b.p(x - 5.0, y + 9.0), b.p(x + 5.0, y + 9.0), b.p(x + 6.0, y - 6.0)], st));
    p.p.line_segment([b.p(x - 2.0, y - 2.0), b.p(x - 2.0, y + 5.0)], st);
    p.p.line_segment([b.p(x + 2.0, y - 2.0), b.p(x + 2.0, y + 5.0)], st);
}

/// A magnifier for the search field, centred at (x, y).
fn draw_search(p: &Painter, x: f32, y: f32, color: Color32) {
    let b = p.b;
    p.p.circle_stroke(b.p(x - 2.0, y - 2.0), 6.0 * b.s, Stroke::new(2.0 * b.s, color));
    p.p.line_segment([b.p(x + 2.5, y + 2.5), b.p(x + 7.0, y + 7.0)], Stroke::new(2.2 * b.s, color));
}

fn hatch(p: &Painter, r: Rect, strength: f32) {
    // an empty or unread sector (Figma DS · Flash Cell · Empty): sand, with its sand border
    let _ = strength;
    let cr = CornerRadius::same((4.0 * p.b.s).round() as u8);
    p.p.rect_filled(r, cr, fw::MAP_EMPTY);
    p.p.rect_stroke(r, cr, Stroke::new(2.0 * p.b.s, fw::BORDER_EMPTY), StrokeKind::Inside);
}

fn lock(p: &Painter, r: Rect) {
    let c = r.center();
    let u = r.width() / 16.0;
    let body = Rect::from_center_size(c + vec2(0.0, 2.5 * u), vec2(8.0 * u, 6.0 * u));
    p.p.rect_filled(body, CornerRadius::ZERO, hex(0x1d1917));
    p.p.circle_stroke(c + vec2(0.0, -1.0 * u), 2.6 * u, Stroke::new(1.6 * u, hex(0x1d1917)));
}

fn lcd_icon(p: &Painter, x: f32, y: f32, synth: bool) {
    // a dot-matrix keyboard (or an empty frame), 32 x 12 "pixels" of 7.4 board units
    let px = 7.4;
    let dot = |cx: usize, cy: usize| p.rect(p.b.r(x + cx as f32 * px, y + cy as f32 * px, px * 0.8, px * 0.8), LCDINK);
    for cx in 0..32 { dot(cx, 0); dot(cx, 11); }
    for cy in 0..12 { dot(0, cy); dot(31, cy); }
    if !synth {
        for cx in (3..29).step_by(2) { dot(cx, 6); }
        return;
    }
    for cx in (3..12).step_by(2) { dot(cx, 3); }
    for cx in (18..29).step_by(3) { dot(cx, 2); dot(cx, 3); }
    for cx in (2..30).step_by(2) { for cy in 6..10 { dot(cx, cy); } }
}

#[derive(Clone, Copy)]
enum Icon { Backup, Restore, Chip, Boot }

fn draw_icon(p: &Painter, icon: Icon, x: f32, y: f32, size: f32, color: Color32) {
    let b = p.b;
    let u = size / 32.0;
    let pt = |a: f32, c: f32| b.p(x + a * u, y + c * u);
    let st = Stroke::new(2.0 * u * b.s, color);
    let line = |pts: &[(f32, f32)]| p.p.add(Shape::line(pts.iter().map(|&(a, c)| pt(a, c)).collect(), st));
    match icon {
        Icon::Backup => {
            line(&[(9.0, 3.0), (19.0, 3.0), (25.0, 9.0), (25.0, 29.0), (9.0, 29.0), (9.0, 3.0)]);
            line(&[(19.0, 3.0), (19.0, 9.0), (25.0, 9.0)]);
            line(&[(3.0, 18.0), (15.0, 18.0)]);
            line(&[(11.0, 14.0), (15.0, 18.0), (11.0, 22.0)]);
        }
        Icon::Restore => {
            line(&[(13.0, 3.0), (23.0, 3.0), (29.0, 9.0), (29.0, 29.0), (13.0, 29.0), (13.0, 3.0)]);
            line(&[(23.0, 3.0), (23.0, 9.0), (29.0, 9.0)]);
            line(&[(19.0, 18.0), (5.0, 18.0)]);
            line(&[(9.0, 14.0), (5.0, 18.0), (9.0, 22.0)]);
        }
        Icon::Chip | Icon::Boot => {
            line(&[(8.0, 8.0), (24.0, 8.0), (24.0, 24.0), (8.0, 24.0), (8.0, 8.0)]);
            for k in [12.0, 16.0, 20.0] {
                line(&[(k, 4.0), (k, 8.0)]);
                line(&[(k, 24.0), (k, 28.0)]);
                line(&[(4.0, k), (8.0, k)]);
                line(&[(24.0, k), (28.0, k)]);
            }
            if let Icon::Boot = icon {
                p.p.circle_filled(pt(23.0, 23.0), 6.0 * u * b.s, KEY_BOT);
                p.p.circle_stroke(pt(23.0, 23.0), 3.2 * u * b.s, st);
            }
        }
    }
}

/// A tileable grain texture for the panel.
fn noise_texture(ctx: &egui::Context) -> TextureHandle {
    let n = 160;
    let mut seed: u32 = 0x9E3779B9;
    let pixels: Vec<Color32> = (0..n * n)
        .map(|_| {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            Color32::from_white_alpha((seed % 14) as u8)
        })
        .collect();
    let image = egui::ColorImage::new([n, n], pixels);
    let opts = TextureOptions { wrap_mode: TextureWrapMode::Repeat, ..TextureOptions::NEAREST };
    ctx.load_texture("noise", image, opts)
}

/// FIRMWAVE_SHOT=file.bmp (with FIRMWAVE_PREVIEW): the window saves a picture of itself and closes, for
/// comparing the design with Figma when the screen can't be captured (a locked Mac, a CI runner)
fn preview_shot(ctx: &egui::Context) {
    use std::sync::atomic::{AtomicU32, Ordering};
    static FRAMES: AtomicU32 = AtomicU32::new(0);
    let Some(path) = std::env::var_os("FIRMWAVE_SHOT") else { return };
    let n = FRAMES.fetch_add(1, Ordering::Relaxed);
    let got = ctx.input(|i| i.events.iter().find_map(|e| match e {
        egui::Event::Screenshot { image, .. } => Some(image.clone()),
        _ => None,
    }));
    if let Some(img) = got {
        let [w, h] = img.size;
        let mut bmp = Vec::with_capacity(54 + w * h * 4);
        let size = (54 + w * h * 4) as u32;
        bmp.extend_from_slice(b"BM");
        bmp.extend_from_slice(&size.to_le_bytes());
        bmp.extend_from_slice(&[0, 0, 0, 0, 54, 0, 0, 0, 40, 0, 0, 0]);
        bmp.extend_from_slice(&(w as i32).to_le_bytes());
        bmp.extend_from_slice(&(-(h as i32)).to_le_bytes());
        bmp.extend_from_slice(&[1, 0, 32, 0, 0, 0, 0, 0]);
        bmp.extend_from_slice(&((w * h * 4) as u32).to_le_bytes());
        bmp.extend_from_slice(&[0; 16]);
        for p in &img.pixels {
            bmp.extend_from_slice(&[p.b(), p.g(), p.r(), 255]);
        }
        let _ = std::fs::write(&path, bmp);
        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
    } else if n == 40 {
        ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(egui::UserData::default()));
    }
    ctx.request_repaint();
}
