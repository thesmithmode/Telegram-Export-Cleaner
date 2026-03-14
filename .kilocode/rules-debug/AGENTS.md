# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Debug Rules

- Логирование настроено в [`src/main/resources/logback.xml`](src/main/resources/logback.xml)
- TelegramExporterException выбрасывает коды ошибок FILE_NOT_FOUND, INVALID_JSON
- Для отладки использовать `-v` флаг в CLI
- Тесты запускать через `mvn test -Dtest=ClassName#methodName`
- REST API: логи смотри в консоли при запуске через Spring Boot
