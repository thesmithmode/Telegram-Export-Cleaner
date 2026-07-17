
import os
import re
import tempfile
from pathlib import Path

import asyncio
from unittest.mock import patch

from java_client import JavaBotClient


REPO_ROOT = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# Regression: /api/** требует X-API-Key. Без этих проверок прод-деплой падает
# на ApiKeyFilter.<init> или worker получает 401 на каждый /api/convert.
# ---------------------------------------------------------------------------


class _HeaderCaptureStream:
    def __init__(self):
        self.headers = None
        self.status_code = 400

    def __call__(self, *args, **kwargs):
        self.headers = kwargs.get("headers")
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def aread(self):
        return b"bad request"


class TestJavaClientApiKeyHeader:

    def test_keeps_x_api_key_out_of_default_client_headers(self):
        with patch("java_client.settings") as mock_settings:
            mock_settings.JAVA_API_BASE_URL = "http://localhost:8080"
            mock_settings.TELEGRAM_BOT_TOKEN = "t"
            mock_settings.JAVA_API_KEY = "super-secret"
            client = JavaBotClient()
            assert "X-API-Key" not in client._http_client.headers

    def test_builds_java_api_headers_when_key_configured(self):
        with patch("java_client.settings") as mock_settings:
            mock_settings.JAVA_API_KEY = "super-secret"
            assert JavaBotClient._java_api_headers() == {"X-API-Key": "super-secret"}

    def test_omits_java_api_headers_when_key_empty(self):
        with patch("java_client.settings") as mock_settings:
            mock_settings.JAVA_API_KEY = ""
            assert JavaBotClient._java_api_headers() == {}

    def test_sends_x_api_key_only_on_java_convert_request(self):
        async def run_case():
            with patch("java_client.settings") as mock_settings:
                mock_settings.JAVA_API_BASE_URL = "http://localhost:8080"
                mock_settings.TELEGRAM_BOT_TOKEN = "t"
                mock_settings.JAVA_API_KEY = "super-secret"
                mock_settings.RETRY_BASE_DELAY = 0.0
                client = JavaBotClient(max_retries=0)
                stream = _HeaderCaptureStream()
                client._http_client.stream = stream
                fd, path = tempfile.mkstemp(suffix=".json")
                try:
                    with os.fdopen(fd, "wb") as f:
                        f.write(b'{"messages":[]}')
                    await client._upload_file_to_java(path)
                    assert stream.headers == {"X-API-Key": "super-secret"}
                finally:
                    try:
                        os.unlink(path)
                    except FileNotFoundError:
                        pass

        asyncio.run(run_case())


class TestDeploymentWiring:

    def test_application_properties_maps_env_to_api_key(self):
        props = (REPO_ROOT / "src/main/resources/application.properties").read_text(encoding="utf-8")
        assert re.search(r"^api\.key=\$\{JAVA_API_KEY:?\}?", props, re.MULTILINE), (
            "application.properties должен содержать 'api.key=${JAVA_API_KEY:}', "
            "иначе Spring не увидит env var и ApiKeyFilter упадёт на старте."
        )

    def test_compose_prod_exposes_key_to_java_bot(self):
        compose = (REPO_ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8")
        java_bot_block = _extract_service_block(compose, "java-bot")
        assert "JAVA_API_KEY=${JAVA_API_KEY}" in java_bot_block, (
            "java-bot должен получать JAVA_API_KEY из .env"
        )

    def test_compose_prod_exposes_key_to_python_worker(self):
        compose = (REPO_ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8")
        worker_block = _extract_service_block(compose, "python-worker")
        assert "JAVA_API_KEY=${JAVA_API_KEY}" in worker_block, (
            "python-worker должен получать JAVA_API_KEY — иначе 401 на /api/convert"
        )

    def test_build_workflow_writes_key_to_env_file(self):
        build = (REPO_ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        assert "JAVA_API_KEY: ${{ secrets.JAVA_API_KEY }}" in build, (
            "build.yml должен передавать JAVA_API_KEY в безопасное окружение шага deploy"
        )
        assert "write_env JAVA_API_KEY" in build, (
            "build.yml должен записывать JAVA_API_KEY в .env через экранирующий helper"
        )
        assert "echo \"JAVA_API_KEY=${{ secrets.JAVA_API_KEY }}\"" not in build, (
            "build.yml не должен подставлять секрет в удалённый shell-скрипт"
        )

    def test_traefik_smoke_reads_unquoted_domain_from_generated_env(self):
        build = (REPO_ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        assert "config --environment | sed -n 's/^TRAEFIK_DASHBOARD_DOMAIN=//p'" in build, (
            "Traefik smoke должен получить значение через Compose parser без dotenv-кавычек"
        )
        assert "grep '^TRAEFIK_DASHBOARD_DOMAIN=' .env" not in build, (
            "сырой grep сохраняет кавычки write_env и ломает Traefik Host matcher"
        )
        assert ". ./.env" not in build, (
            "Compose dotenv нельзя исполнять как shell-файл: правила escaping различаются"
        )

    def test_env_writer_uses_compose_compatible_escaping(self):
        build = (REPO_ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        assert r"value=${value//\\/\\\\}" in build, (
            "write_env должен сначала экранировать backslash для Compose dotenv"
        )
        assert r'value=${value//\"/\\\"}' in build, (
            "write_env должен экранировать двойную кавычку в double-quoted dotenv"
        )
        assert r"value=${value//\$/\$\$}" in build, (
            "write_env должен удваивать dollar, чтобы Compose не интерполировал секрет"
        )
        assert "printf '%s=\"%s\"\\n' \"$name\" \"$value\"" in build
        assert "value=${value//\'/\'\\\'\'}" not in build, (
            "shell-конкатенация кавычек не поддерживается Docker Compose dotenv parser"
        )

    def test_rollback_restarts_compose_with_rollback_tag(self):
        build = (REPO_ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        assert "PREV_JAVA_ID=$(docker inspect --format='{{.Image}}' telegram-export-java-bot" in build
        assert "PREV_WORKER_ID=$(docker inspect --format='{{.Image}}' telegram-export-worker" in build
        assert "IMAGE_TAG=rollback docker compose" in build, (
            "compose использует фиксированный IMAGE_TAG, поэтому смены :latest недостаточно для отката"
        )
        assert 'if [ -z "$PREV_JAVA_ID" ] || [ -z "$PREV_WORKER_ID" ]; then' in build, (
            "неполная rollback-точка должна останавливать стек, а не запускать смешанную версию"
        )


def _extract_service_block(compose: str, service: str) -> str:
    """Грубый срез YAML-блока сервиса до следующего сервиса того же уровня."""
    lines = compose.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.startswith(f"  {service}:"):
            start = i
            break
    assert start is not None, f"service {service} не найден в compose"
    end = len(lines)
    for j in range(start + 1, len(lines)):
        stripped = lines[j]
        if stripped.startswith("  ") and not stripped.startswith("   ") and stripped.rstrip().endswith(":"):
            end = j
            break
    return "\n".join(lines[start:end])
