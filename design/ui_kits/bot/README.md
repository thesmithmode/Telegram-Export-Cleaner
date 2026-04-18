# Bot UI Kit — Telegram Export Cleaner

Interactive recreation of the bot's chat experience inside a Telegram client frame. Not a storybook — open `index.html` to see a real end-to-end flow.

## Flow

1. `/start` → help text
2. Send `@durov` or `https://t.me/durov`
3. Pick a date range via inline keyboard
4. Bot shows "task accepted" with cancel button
5. Click cancel or wait for completion

## Components

- `TelegramChat.jsx` — chat frame (header, wallpaper, message list, composer)
- `Bubble.jsx` — in/out message bubbles with Telegram rounding
- `InlineKeyboard.jsx` — tap-grid attached below a bot message
- `StatusChip.jsx` — queued/processing/done bar used inside bubbles

Source truth: `src/main/java/com/tcleaner/bot/ExportBot.java` — all button labels, all status copy.
