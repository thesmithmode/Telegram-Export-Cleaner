# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Architect Mode Rules

- Spring Boot 3.3.0 REST API + CLI приложение
- Java 25, Maven, JUnit 5, AssertJ
- Архитектура: Controller -> Service -> Processor (TelegramExporter -> MessageProcessor)
- JSON парсинг через Jackson, CLI через JCommander
- Фильтрация: MessageFilter, DateFormatter, MarkdownParser
- Временные файлы через TempDirectory (AutoCloseable inner class)
- Docker поддержка через docker-compose
