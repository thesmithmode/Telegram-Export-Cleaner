
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
        props = (REPO_ROOT / "src/main/resources/application.properties").read_text()
        assert re.search(r"^api\.key=\$\{JAVA_API_KEY:?\}?", props, re.MULTILINE), (
            "application.properties должен содержать 'api.key=${JAVA_API_KEY:}', "
            "иначе Spring не увидит env var и ApiKeyFilter упадёт на старте."
        )

    def test_compose_prod_exposes_key_to_java_bot(self):
        compose = (REPO_ROOT / "docker-compose.prod.yml").read_text()
        java_bot_block = _extract_service_block(compose, "java-bot")
        assert "JAVA_API_KEY=${JAVA_API_KEY}" in java_bot_block, (
            "java-bot должен получать JAVA_API_KEY из .env"
        )

    def test_compose_prod_exposes_key_to_python_worker(self):
        compose = (REPO_ROOT / "docker-compose.prod.yml").read_text()
        worker_block = _extract_service_block(compose, "python-worker")
        assert "JAVA_API_KEY=${JAVA_API_KEY}" in worker_block, (
            "python-worker должен получать JAVA_API_KEY — иначе 401 на /api/convert"
        )

    def test_build_workflow_writes_key_to_env_file(self):
        build = (REPO_ROOT / ".github/workflows/build.yml").read_text()
        assert "JAVA_API_KEY=${{ secrets.JAVA_API_KEY }}" in build, (
            "build.yml должен писать JAVA_API_KEY в .env на сервере из GitHub Secrets"
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
