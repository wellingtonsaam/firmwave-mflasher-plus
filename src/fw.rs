//! FirmWave's design tokens: the Foundations of the Neo Brutalism design system
//! (Figma "Foundations · FirmWave", node 283:9836). Each name is the variable's,
//! as its description in Figma says (`fw::BG_APP` is `color/bg/app`); web uses
//! `--fw-color-bg-app` and Android `@color/fw_bg_app` for the same token.
#![allow(dead_code)]

use eframe::egui::Color32;

const fn hex(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

// ---- primitives (FirmWave · Primitives) ----
pub const CREAM_100: Color32 = hex(0xFFF4DC);
pub const CREAM_50: Color32 = hex(0xFFFDF5);
pub const WHITE: Color32 = hex(0xFFFFFF);
pub const BLACK: Color32 = hex(0x000000);
pub const GRAY_700: Color32 = hex(0x4A4A4A);
pub const GRAY_500: Color32 = hex(0x6B6B6B);
pub const GRAY_300: Color32 = hex(0xBDBDBD);
pub const GRAY_800: Color32 = hex(0x3A3A3A);
pub const LIME: Color32 = hex(0xB8FF6B);
pub const YELLOW_500: Color32 = hex(0xFFE500);
pub const YELLOW_300: Color32 = hex(0xFFE066);
pub const PURPLE: Color32 = hex(0xA388EE);
pub const LILAC: Color32 = hex(0xC4A1FF);
pub const ORANGE_300: Color32 = hex(0xFF9B71);
pub const ORANGE_600: Color32 = hex(0xFF4911);
pub const GREEN_300: Color32 = hex(0x90EE90);
pub const GREEN_LED: Color32 = hex(0x2FFF2F);
pub const GREEN_800: Color32 = hex(0x1F7A3A);
pub const GREEN_50: Color32 = hex(0xF2FFE0);
pub const CYAN: Color32 = hex(0x7DF9FF);
pub const PINK_200: Color32 = hex(0xFFB2EF);
pub const PINK_500: Color32 = hex(0xFF69B4);
pub const CORAL: Color32 = hex(0xFF6B6B);
pub const SAND_200: Color32 = hex(0xF3EAD3);
pub const SAND_400: Color32 = hex(0xB9AE94);

// ---- semantic (FirmWave · Color): what each colour does ----
pub const BG_APP: Color32 = CREAM_100;
pub const BG_LOG: Color32 = CREAM_50;
pub const SURFACE_CARD: Color32 = WHITE;
pub const SURFACE_SAVED: Color32 = GREEN_50;
pub const SURFACE_EMPTY: Color32 = SAND_200;
pub const INK_PRIMARY: Color32 = BLACK;
pub const INK_MUTED: Color32 = GRAY_700;
pub const INK_PLACEHOLDER: Color32 = GRAY_500;
pub const INK_INVERSE: Color32 = WHITE;
pub const INK_OK: Color32 = GREEN_800;
pub const BORDER_DEFAULT: Color32 = BLACK;
pub const BORDER_EMPTY: Color32 = SAND_400;
pub const SHADOW_DEFAULT: Color32 = BLACK;
pub const ACCENT_PRIMARY: Color32 = YELLOW_500;
pub const ACCENT_TAB: Color32 = PURPLE;
pub const ACCENT_BRAND: Color32 = ORANGE_600;
pub const STATUS_OK: Color32 = GREEN_300;
pub const STATUS_WARN: Color32 = ORANGE_300;
pub const LCD_NORMAL: Color32 = LIME;
pub const LCD_ALERT: Color32 = ORANGE_300;
pub const LED_ON: Color32 = GREEN_LED;
pub const LED_OFF: Color32 = GRAY_800;
pub const OP_SAVE: Color32 = CYAN;
pub const OP_RESTORE: Color32 = PINK_200;
pub const OP_INSTALL: Color32 = LILAC;
pub const MAP_BOOT: Color32 = CORAL;
pub const MAP_FIRMWARE: Color32 = CYAN;
pub const MAP_SETTINGS: Color32 = YELLOW_300;
pub const MAP_PAIRINGS: Color32 = PURPLE;
pub const MAP_SOUNDS: Color32 = PINK_500;
pub const MAP_MAC: Color32 = GRAY_300;
pub const MAP_FREE: Color32 = WHITE;
pub const MAP_WRITTEN: Color32 = BLACK;
pub const MAP_EMPTY: Color32 = SAND_200;
pub const WINDOW_CLOSE: Color32 = CORAL;
pub const WINDOW_MINIMIZE: Color32 = YELLOW_300;
pub const WINDOW_ZOOM: Color32 = GREEN_300;

// ---- size (FirmWave · Size), in board units ----
pub const RADIUS_XL: f32 = 12.0;
pub const RADIUS_FULL: f32 = 999.0;
pub const STROKE_THIN: f32 = 2.0;
pub const STROKE_DEFAULT: f32 = 3.0;
pub const SHADOW_KEY: f32 = 5.0;
pub const SHADOW_CARD: f32 = 4.0;
