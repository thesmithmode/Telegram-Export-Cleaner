# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Ask Mode Rules

- Проект для очистки Telegram экспорта (result.json) в формат для LLM
- Обработка через MessageProcessor -> формат "YYYYMMDD text"
- REST API: /api/convert, /api/convert/json, /api/health
- CLI: java -jar target/telegram-cleaner-1.0.0.jar -i <path>
- Тесты в src/test/java/com/tcleaner/
