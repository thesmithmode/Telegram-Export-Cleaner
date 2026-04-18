# Telegram Export Cleaner — Design System

A design system for **Telegram Export Cleaner** — a Telegram bot that exports chat history to a cleaned text file, plus a companion web dashboard for admins to monitor export statistics.

> 👉 **Try the bot:** [@Export_Cleaner_bot](https://t.me/Export_Cleaner_bot)

---

## Products

The brand covers two product surfaces:

1. **Telegram Bot** (`@Export_Cleaner_bot`) — The primary product. A chat-first experience inside Telegram itself. Users send a `@username` or `t.me/...` link, pick a date range via inline keyboard buttons, and receive an `output.txt` file. All copy is in **Russian**, with heavy use of emoji as visual anchors for buttons.
2. **Admin Dashboard** (`/dashboard/*`) — A Spring Boot + Thymeleaf SSR web app for operators. Shows KPIs (exports, messages, bytes, users), time-series charts, top chats, event logs. Monochrome palette with a single blue accent. Chart.js for visualization. Login via Telegram Login Widget.

---

## Sources

All design context was lifted from the public repo:

- **Repo:** [thesmithmode/Telegram-Export-Cleaner](https://github.com/thesmithmode/Telegram-Export-Cleaner) @ `main`
- **Bot copy (Russian):** `src/main/java/com/tcleaner/bot/ExportBot.java`, `src/main/resources/messages.properties`
- **Bot behaviour spec:** `docs/BOT.md`
- **Dashboard palette + components:** `src/main/resources/static/dashboard/css/app.css`
- **Dashboard markup:** `src/main/resources/templates/dashboard/*.html`
- **Dashboard JS / Chart.js usage:** `src/main/resources/static/dashboard/js/app.js`, `js/pages/*.js`
- **Architecture spec:** `docs/DASHBOARD.md`, `docs/ARCHITECTURE.md`

No Figma was provided. No dedicated logo/brand asset file exists in the repo — the "brand" is essentially the dashboard CSS, the bot's emoji-prefixed button language, and the Telegram UI in which the bot lives.

---

## Content Fundamentals

**Language:** Russian (ru). All user-facing copy is Russian; the codebase (class names, comments, docs) mixes Russian and English — Russian for UX strings, English for technical identifiers.

**Tone:** Terse, functional, operator-like. No marketing warmth. No "we" voice. Short sentences. Imperative verbs ("Отправьте", "Выберите", "Введите"). Treats the user as competent — no hand-holding.

**Address form:** The bot uses **formal "Вы"** in help text ("Отправьте одно из…") and in neutral instructions. Error messages use plain imperatives without a pronoun ("Неверный формат").

**Emoji usage (critical to the brand):** Emoji are **load-bearing UI**, not decoration. Every inline-keyboard button starts with a single emoji that functions as its icon. The set is small and consistent:
- `📦` whole chat · `⏱` hours · `🗓` days · `📅` date range · `⏮` from start · `⏭` to today · `◀️` back
- `⏳` queued / waiting · `⚙️` processing · `⚡` cache hit · `📋` menu · `❌` cancel / error · `✅` success / confirmation

Messages also open with an emoji status-chip (`⏳ Задача принята!`, `✅ Экспорт отменён.`, `❌ Неверный формат.`). The dashboard UI, by contrast, uses **zero emoji** — it uses text `status-chip` pills instead (`completed`, `failed`, `processing`, `queued`, `cancelled`).

**Casing:** Sentence case everywhere. No all-caps. KPI labels in the dashboard are the one exception — they're uppercase with `.5px` letter-spacing (`ЭКСПОРТОВ`, `СООБЩЕНИЙ`).

**Copy samples:**
- Help: `Этот бот экспортирует историю Telegram-чата и отправляет очищенный текст.`
- Prompt: `Отправьте одно из:\n• username: @durov\n• ссылка: https://t.me/durov`
- Queue: `⏳ Задача принята!\n\nID: 4f3a…\nЧат: @durov\n📅 От: 01.01.2024 — До: сегодня`
- Cache hit: `⚡ Данные в кэше — результат будет быстро!`
- Error: `❌ Неверный формат (дд.мм.гггг)`

**Vibe:** Technical utility. Imagine a Unix command-line tool that grew a friendly Telegram face. Numbers, IDs, byte counts — exposed, not hidden.

---

## Visual Foundations

**Identity in two halves.** The bot's "visuals" are whatever Telegram renders — message bubbles, inline-keyboard pill buttons, monospaced IDs. The dashboard is a utilitarian admin panel: white surface on cool grey, single blue accent, thin borders, subtle shadow.

**Colors** — sourced directly from `dashboard/css/app.css`:
- `--bg: #f5f6f8` (cool off-white page bg)
- `--surface: #ffffff` (cards, tables)
- `--border: #e3e6ea` (1px hairline, everywhere)
- `--text: #1f2430` (near-black, slightly cool)
- `--muted: #666e7b` (secondary text, labels)
- `--accent: #2563eb` (blue — only accent; buttons, links, charts)
- `--accent-hover: #1e50c8`
- `--danger: #d64545` / `--success: #2b8a3e`
- Status-chip tints are pastel pairs: success `#e8f5ea`/`#2b8a3e`, danger `#fbe9e9`/`#d64545`, processing `#fff4e5`/`#b7791f`, queued `#e5edff`/`#3358d4`, cancelled `#f5f6f8`/`#666e7b`.
- Telegram-brand blue `#229ED9` is used for bot-side moments (widget, bot-avatar placeholder) to stay coherent inside the Telegram client.

**Type:** System-font stack only (`-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif`). Base size 14px. The dashboard has no webfonts, no display face, no serif. **Monospaced** (`ui-monospace, SFMono-Regular, Menlo, monospace`) is used for task IDs and chat handles where precision reads well.

**Spacing:** Multiples of 4 — 4, 8, 12, 16, 24, 32. Page max-width 1200px centered. Main gutter 24px. Card padding 14–16px.

**Borders + radii:** Every card/button/input has `1px solid var(--border)` and `--radius: 6px`. No loud rounds, no pill CTAs. Status chips are a rare exception at `border-radius: 12px` (fully rounded).

**Shadows:** One shadow token — `--shadow-sm: 0 1px 2px rgba(0,0,0,.05)` — applied to header, login card, KPIs, chart blocks, tables. No elevated floating cards, no glow.

**Backgrounds:** Flat color. No gradients. No images. No textures. The body is a slab of `#f5f6f8`.

**Animation:** Near-zero. Only transition is `background-color .1s ease-in-out` on button hover. No page transitions, no skeletons, no bouncing. Keep it that way.

**Hover states:** Darken the fill (blue → `#1e50c8`), or shift muted text to `--text` on nav links. Underline links on hover. No scale, no glow.

**Press states:** No explicit press treatment — browser default.

**Transparency / blur:** Not used. Opaque surfaces only.

**Imagery:** None in the product. For mocks and slides, prefer grey placeholder rects at 6px radius over illustrations. If imagery is needed, go cool-tone, desaturated, utility-adjacent (terminal screenshots, abstract grids).

**Layout rules:**
- Dashboard: sticky-feeling header (not actually sticky), `max-width:1200px` main, one column on mobile, CSS grid (`repeat(auto-fit, minmax(160px,1fr))`) for KPI rows.
- Bot: single column of message bubbles; inline keyboards are 1-2 buttons wide.

**Telegram chrome as layout context.** When showing the bot, show it inside a faithful Telegram chat frame — dark/light variants, message bubbles with tails, inline-keyboard grids below bot messages. The bot IS its Telegram presentation.

---

## Iconography

**Two completely separate icon vocabularies — do not mix them.**

1. **Inside the bot → emoji only.** Single emoji prefix on every button and every status line. Covered exhaustively above in Content Fundamentals. Never hand-draw SVGs for bot UI — emoji render natively everywhere Telegram runs, and the brand's voice depends on them.

2. **Inside the dashboard → no icons.** The original CSS ships zero icons. Navigation is plain text links. Status is communicated through coloured text pills (`.status-chip--completed` etc.), not glyphs. KPI labels are uppercase text.

**If icons become necessary for the dashboard** (e.g. building richer admin views), substitute [**Lucide**](https://lucide.dev) at 16–20px, 1.5px stroke, `currentColor`. Lucide's neutral, thin-stroke, geometric style matches the dashboard's restraint. **⚠ This is a substitution** — flag it to the product owner; the live dashboard has no icons today.

**Logo:** The repo has no logo file. The wordmark is literally the string "Telegram Export Cleaner" in the system font, 16px, weight 600, in `--text`. A placeholder glyph — a broom over a chat-bubble, or the paper-airplane — is included in `assets/logo-mark.svg` as a stopgap; **ask the user for a real mark.**

**No Unicode pseudo-icons** (▶ ◆ ★ etc.) anywhere.

---

## Index / manifest

Root files:

- `README.md` — this file
- `colors_and_type.css` — CSS custom properties for colors, type, spacing, radius, shadow
- `SKILL.md` — skill manifest (for Claude Code / agent use)
- `fonts/` — (empty; the system uses native stack, no webfonts)
- `assets/` — logos, icon sample SVGs, brand marks
- `preview/` — small HTML cards that populate the Design System tab
- `ui_kits/bot/` — Telegram bot UI kit (chat frame + message bubbles + inline keyboards)
- `ui_kits/dashboard/` — Admin dashboard UI kit (header, KPIs, tables, charts, status chips)

UI kits:

- [`ui_kits/bot/index.html`](ui_kits/bot/index.html) — interactive click-thru of the bot flow inside a Telegram chat frame
- [`ui_kits/dashboard/index.html`](ui_kits/dashboard/index.html) — Overview page recreation with KPIs, timeseries chart, top chats

---

## Caveats

- **No logo asset exists** in the upstream repo. A placeholder mark is provided; ask for a real one.
- **No webfont** — the product ships with the OS stack. No substitution needed, but noted.
- **Dashboard icons are a substitution** — Lucide is proposed; live product has none.
- **Russian copy** is the primary voice; English is only used for technical labels and fallbacks in mocks.
