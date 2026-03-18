# 🔬 Фаза 2: Python Export Worker - Детальный план на микро-шаги

**Дата:** 2026-03-18
**Версия:** 1.0
**Сложность:** Высокая
**Время на фазу:** 8-10 часов (32 шага × 15-20 мин)

---

## ✅ Микро-шаг 1: Исследование Pyrogram JSON структуры (20 мин)

### Что нужно сделать:
1. Создать тестовый Pyrogram скрипт для экспорта одного чата
2. Получить Message объект
3. Распечатать JSON структуру Message

### Проверка успеха:
```python
# Должны увидеть структуру Message:
{
    'id': 123,
    'date': datetime,
    'text': 'message text',
    'entities': [...],
    'media': ...,
    'forward_from': ...,
}
```

### Код для проверки:
```bash
mkdir -p export-worker/research
python3 -c "from pyrogram.types import Message; print(Message.__doc__)"
```

---

## ✅ Микро-шаг 2: Изучить Telegram Desktop export format (20 мин)

### Что нужно сделать:
1. Посмотреть existing Java код в проекте
2. Найти класс TelegramExporter
3. Разобраться в структуре result.json

### Команды:
```bash
grep -r "result.json" src/
grep -r "class.*Exporter" src/
```

### Проверка успеха:
Понимаю структуру:
```json
{
  "about": "...",
  "chats": [...],
  "messages": [...]
}
```

---

## ✅ Микро-шаг 3: Создать структуру проекта export-worker (10 мин)

### Файлы:
```
export-worker/
├── main.py                  # Entry point
├── pyrogram_client.py       # Pyrogram integration
├── json_converter.py        # Message → JSON
├── queue_consumer.py        # Redis listener
├── java_client.py           # HTTP to Java
├── models.py                # Pydantic models
├── config.py                # Configuration
├── requirements.txt         # Dependencies
├── Dockerfile               # Container
├── .dockerignore            # Docker ignore
├── session/                 # Pyrogram session (volume)
├── logs/                    # Logs directory
└── tests/
    ├── __init__.py
    ├── test_json_converter.py
    ├── test_queue_consumer.py
    └── test_models.py
```

### Команды:
```bash
mkdir -p export-worker/{session,logs,tests}
touch export-worker/{main.py,pyrogram_client.py,json_converter.py,queue_consumer.py,java_client.py,models.py,config.py,requirements.txt,Dockerfile,.dockerignore}
touch export-worker/tests/{__init__.py,test_json_converter.py,test_queue_consumer.py,test_models.py}
```

---

## ✅ Микро-шаг 4: Написать requirements.txt (10 мин)

### Файл: export-worker/requirements.txt
```
pyrogram==1.4.16
aioredis==2.0.1
aiohttp==3.8.5
requests==2.31.0
python-dotenv==1.0.0
pydantic==1.10.13
pyyaml==6.0.1
pytest==7.4.0
pytest-asyncio==0.21.1
```

### Проверка:
```bash
pip install -r export-worker/requirements.txt
```

---

## ✅ Микро-шаг 5: Написать config.py (15 мин)

### Задача:
Centralized configuration из .env файла

### Код:
```python
from pydantic import BaseSettings
import os

class Settings(BaseSettings):
    telegram_api_id: int
    telegram_api_hash: str
    telegram_phone_number: str

    redis_host: str = 'localhost'
    redis_port: int = 6379
    redis_db: int = 0

    java_app_url: str = 'http://localhost:8080'
    workers: int = 5
    log_level: str = 'INFO'

    class Config:
        env_file = '.env'
        env_file_encoding = 'utf-8'

settings = Settings()
```

### Проверка:
```bash
python3 export-worker/config.py
# Должны напечататься все значения из .env
```

---

## ✅ Микро-шаг 6: Написать models.py (20 мин)

### Задача:
Pydantic модели для типизации

### Модели:
```python
class ExportTask(BaseModel):
    task_id: str
    user_id: int
    chat_id: int
    date_from: Optional[datetime] = None
    date_to: Optional[datetime] = None
    filter_keywords: Optional[List[str]] = None

class ExportResult(BaseModel):
    task_id: str
    file_id: str
    status: str  # COMPLETED, FAILED
    error: Optional[str] = None

class Message(BaseModel):
    id: int
    date: datetime
    text: Optional[str] = None
    sender: Optional[int] = None
    ...
```

### Проверка:
```bash
python3 -c "from export-worker.models import ExportTask; print(ExportTask.schema())"
```

---

## ✅ Микро-шаг 7: Написать Dockerfile (15 мин)

### Файл: export-worker/Dockerfile
```dockerfile
FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y gcc && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN mkdir -p session logs

ENV PYTHONUNBUFFERED=1

CMD ["python", "main.py"]
```

### Проверка:
```bash
docker build -f export-worker/Dockerfile -t export-worker:test .
```

---

## ✅ Микро-шаг 8: Написать pyrogram_client.py - инициализация (20 мин)

### Задача:
Создать PyrogramClient класс с аутентификацией

### Основной код:
```python
from pyrogram import Client
from config import settings
import logging

class PyrogramClient:
    def __init__(self):
        self.client = Client(
            name='session',
            api_id=settings.telegram_api_id,
            api_hash=settings.telegram_api_hash,
            workdir='./session'
        )
        self.logger = logging.getLogger(__name__)

    async def authenticate(self):
        """Authenticate with phone number"""
        await self.client.connect()
        if not await self.client.is_user_authorized():
            await self.client.send_code_request(settings.telegram_phone_number)
            code = input("Enter code: ")
            await self.client.sign_in(settings.telegram_phone_number, code)
        self.logger.info("Authentication successful")

    async def disconnect(self):
        """Disconnect from Telegram"""
        await self.client.disconnect()
```

### Проверка:
```python
# Тест подключения
client = PyrogramClient()
await client.authenticate()  # Нужен SMS код
await client.disconnect()
```

---

## ✅ Микро-шаг 9: Написать pyrogram_client.py - экспорт чата (25 мин)

### Задача:
Метод для экспорта истории чата

### Код:
```python
async def get_chat_history(
    self,
    chat_id: int,
    date_from: Optional[datetime] = None,
    date_to: Optional[datetime] = None,
    limit: Optional[int] = None
) -> List[Message]:
    """Export chat history from Telegram"""
    try:
        messages = []

        # get_chat_history возвращает итератор
        async for message in self.client.get_chat_history(
            chat_id,
            offset_date=date_from,
            limit=limit or 100000
        ):
            # Фильтр по дате если нужно
            if date_to and message.date > date_to:
                continue
            if date_from and message.date < date_from:
                break

            messages.append(message)

            if len(messages) % 100 == 0:
                self.logger.info(f"Exported {len(messages)} messages...")

        self.logger.info(f"Export completed: {len(messages)} messages")
        return messages

    except Exception as e:
        self.logger.error(f"Export failed: {e}")
        raise
```

### Проверка:
```python
# Тест экспорта
messages = await client.get_chat_history(chat_id=-1001234567890)
print(f"Got {len(messages)} messages")
```

---

## ✅ Микро-шаг 10: Написать json_converter.py - инициализация (15 мин)

### Задача:
Создать JSONConverter класс

### Код:
```python
from datetime import datetime
from typing import List, Dict, Any
import json

class JSONConverter:
    def __init__(self):
        self.logger = logging.getLogger(__name__)

    def convert_messages_to_json(self, messages: List) -> Dict[str, Any]:
        """
        Convert Pyrogram messages to result.json format
        Compatible with Telegram Desktop export
        """
        result = {
            "about": "Telegram chat export",
            "chats": {
                "about": "Chat information",
                "list": []
            },
            "messages": []
        }

        return result
```

---

## ✅ Микро-шаг 11: JSON Converter - обработка сообщений (30 мин)

### Задача:
Преобразовать каждое сообщение

### Код:
```python
def convert_message(self, message) -> Dict[str, Any]:
    """Convert single Pyrogram message to JSON format"""

    msg = {
        "id": message.id,
        "type": "message",
        "date": int(message.date.timestamp()),
        "date_unixtime": str(int(message.date.timestamp()))
    }

    # Add text if exists
    if message.text:
        msg["text"] = message.text

    # Handle entities (bold, italic, links, etc)
    if message.entities:
        msg["text_entities"] = self._convert_entities(message.entities, message.text)

    # Handle sender
    if message.from_user:
        msg["from"] = {
            "id": message.from_user.id,
            "first_name": message.from_user.first_name,
            "last_name": message.from_user.last_name or "",
            "username": message.from_user.username or ""
        }

    # Handle media
    if message.media:
        msg["media"] = self._convert_media(message.media)

    # Handle forwards
    if message.forward_from:
        msg["forward_from"] = {
            "id": message.forward_from.id,
            "first_name": message.forward_from.first_name,
            "username": message.forward_from.username or ""
        }

    return msg

def _convert_entities(self, entities, text: str) -> List[Dict]:
    """Convert text entities (bold, italic, links)"""
    result = []

    for entity in entities:
        ent = {
            "type": self._entity_type_map.get(entity.__class__.__name__, "unknown"),
            "offset": entity.offset,
            "length": entity.length
        }

        # Extract URL for links
        if hasattr(entity, 'url'):
            ent["url"] = entity.url

        result.append(ent)

    return result

def _convert_media(self, media) -> Dict[str, Any]:
    """Convert media information"""
    media_type = media.__class__.__name__

    return {
        "type": media_type,
        "mime_type": getattr(media, 'mime_type', ''),
        "file_name": getattr(media, 'file_name', '')
    }
```

---

## ✅ Микро-шаг 12: JSON Converter - финальный JSON (15 мин)

### Задача:
Собрать все в финальный JSON

### Код:
```python
def finalize_json(self, messages: List, chat_info: Dict) -> Dict[str, Any]:
    """Create final result.json structure"""

    converted_messages = [self.convert_message(msg) for msg in messages]

    result = {
        "about": "Telegram chat export",
        "chats": {
            "about": "Chat information",
            "list": [{
                "id": chat_info.get('id'),
                "name": chat_info.get('title'),
                "type": chat_info.get('type', 'supergroup'),
                "messages_count": len(messages)
            }]
        },
        "messages": converted_messages
    }

    return result

def to_json_string(self, data: Dict) -> str:
    """Convert to JSON string with proper formatting"""
    return json.dumps(data, indent=2, ensure_ascii=False, default=str)
```

---

## ✅ Микро-шаг 13: Написать queue_consumer.py - инициализация (15 мин)

### Задача:
Создать QueueConsumer для слушания Redis

### Код:
```python
import aioredis
import json
from config import settings

class QueueConsumer:
    def __init__(self):
        self.redis = None
        self.logger = logging.getLogger(__name__)

    async def connect(self):
        """Connect to Redis"""
        self.redis = await aioredis.create_redis_pool(
            f'redis://{settings.redis_host}:{settings.redis_port}/{settings.redis_db}',
            encoding='utf-8'
        )
        self.logger.info("Connected to Redis")

    async def disconnect(self):
        """Disconnect from Redis"""
        if self.redis:
            self.redis.close()
            await self.redis.wait_closed()
            self.logger.info("Disconnected from Redis")
```

---

## ✅ Микро-шаг 14: Queue Consumer - чтение задач (20 мин)

### Задача:
Метод для чтения задач из export-tasks stream

### Код:
```python
async def read_tasks(self, count: int = 10) -> List[Dict]:
    """Read tasks from export-tasks stream"""
    try:
        # XREAD последние задачи
        tasks_raw = await self.redis.xread(
            {b'export-tasks': b'0-0'},  # From beginning
            count=count
        )

        tasks = []

        if tasks_raw:
            for stream, messages in tasks_raw:
                for msg_id, msg_data in messages:
                    task = {
                        'id': msg_id.decode() if isinstance(msg_id, bytes) else msg_id,
                        **msg_data
                    }
                    tasks.append(task)

        self.logger.info(f"Read {len(tasks)} tasks from queue")
        return tasks

    except Exception as e:
        self.logger.error(f"Failed to read tasks: {e}")
        raise
```

---

## ✅ Микро-шаг 15: Queue Consumer - сохранение результатов (15 мин)

### Задача:
Методы для сохранения результатов в Redis

### Код:
```python
async def save_result(self, task_id: str, file_id: str, status: str, error: str = None):
    """Save result to export-results stream"""
    try:
        result_data = {
            'task_id': task_id,
            'file_id': file_id,
            'status': status,
            'error': error or ''
        }

        await self.redis.xadd(b'export-results', result_data)
        self.logger.info(f"Saved result for task {task_id}: {status}")

    except Exception as e:
        self.logger.error(f"Failed to save result: {e}")
        raise

async def delete_task(self, task_id: str):
    """Delete task from queue after processing"""
    try:
        await self.redis.xdel(b'export-tasks', task_id)
        self.logger.info(f"Deleted task {task_id} from queue")
    except Exception as e:
        self.logger.error(f"Failed to delete task: {e}")
```

---

## ✅ Микро-шаг 16: Написать java_client.py - инициализация (10 мин)

### Задача:
HTTP клиент для отправки на Java сервис

### Код:
```python
import aiohttp
from config import settings

class JavaClient:
    def __init__(self):
        self.base_url = settings.java_app_url
        self.session = None
        self.logger = logging.getLogger(__name__)

    async def init_session(self):
        """Initialize aiohttp session"""
        self.session = aiohttp.ClientSession()

    async def close_session(self):
        """Close aiohttp session"""
        if self.session:
            await self.session.close()
```

---

## ✅ Микро-шаг 17: Java Client - upload JSON (25 мин)

### Задача:
Метод для отправки JSON на /api/files/upload

### Код:
```python
async def upload_json(self, json_data: str, file_name: str = "export.json") -> str:
    """
    Upload JSON to Java app
    Returns file_id if successful
    """
    if not self.session:
        await self.init_session()

    try:
        # Create form data
        data = aiohttp.FormData()
        data.add_field('file', json_data, filename=file_name, content_type='application/json')

        # POST to Java app
        async with self.session.post(
            f'{self.base_url}/api/files/upload',
            data=data,
            timeout=aiohttp.ClientTimeout(total=300)  # 5 минут
        ) as resp:
            if resp.status == 200:
                result = await resp.json()
                file_id = result.get('fileId')
                self.logger.info(f"Upload successful: {file_id}")
                return file_id
            else:
                error = await resp.text()
                raise Exception(f"Upload failed: {resp.status} - {error}")

    except Exception as e:
        self.logger.error(f"Upload error: {e}")
        raise
```

---

## ✅ Микро-шаг 18: Java Client - retry logic (20 мин)

### Задача:
Добавить exponential backoff для надежности

### Код:
```python
async def upload_json_with_retry(self, json_data: str, max_retries: int = 3) -> str:
    """Upload with exponential backoff retry"""

    for attempt in range(max_retries):
        try:
            return await self.upload_json(json_data)

        except Exception as e:
            if attempt == max_retries - 1:
                self.logger.error(f"Upload failed after {max_retries} attempts")
                raise

            wait_time = 2 ** attempt  # 1s, 2s, 4s
            self.logger.warning(f"Attempt {attempt + 1} failed, retrying in {wait_time}s...")
            await asyncio.sleep(wait_time)
```

---

## ✅ Микро-шаг 19: Написать main.py - инициализация (15 мин)

### Задача:
Entry point для Worker'а

### Код:
```python
import asyncio
import logging
from config import settings
from pyrogram_client import PyrogramClient
from queue_consumer import QueueConsumer
from java_client import JavaClient
from json_converter import JSONConverter

# Configure logging
logging.basicConfig(
    level=settings.log_level,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class ExportWorker:
    def __init__(self):
        self.pyrogram = PyrogramClient()
        self.queue = QueueConsumer()
        self.java_client = JavaClient()
        self.converter = JSONConverter()
        self.logger = logging.getLogger(__name__)

    async def initialize(self):
        """Initialize all components"""
        self.logger.info("Initializing Export Worker...")

        # Connect to Pyrogram
        await self.pyrogram.authenticate()

        # Connect to Redis
        await self.queue.connect()

        # Initialize HTTP client
        await self.java_client.init_session()

        self.logger.info("Initialization complete")

    async def cleanup(self):
        """Cleanup resources"""
        self.logger.info("Cleaning up...")
        await self.pyrogram.disconnect()
        await self.queue.disconnect()
        await self.java_client.close_session()
```

---

## ✅ Микро-шаг 20: Main - обработка одной задачи (25 мин)

### Задача:
Основной flow обработки задачи

### Код:
```python
async def process_task(self, task: Dict) -> bool:
    """Process single export task"""
    task_id = task.get('id')
    user_id = task.get('user_id')
    chat_id = int(task.get('chat_id'))

    self.logger.info(f"Processing task {task_id}: user={user_id}, chat={chat_id}")

    try:
        # 1. Get chat info
        chat = await self.pyrogram.client.get_chat(chat_id)
        self.logger.info(f"Chat: {chat.title}")

        # 2. Export history
        messages = await self.pyrogram.get_chat_history(
            chat_id=chat_id,
            date_from=task.get('date_from'),
            date_to=task.get('date_to')
        )
        self.logger.info(f"Exported {len(messages)} messages")

        # 3. Convert to JSON
        json_data = self.converter.finalize_json(
            messages=messages,
            chat_info={
                'id': chat.id,
                'title': chat.title,
                'type': 'supergroup'
            }
        )
        json_string = self.converter.to_json_string(json_data)

        # 4. Upload to Java
        file_id = await self.java_client.upload_json_with_retry(json_string)

        # 5. Save result
        await self.queue.save_result(
            task_id=task_id,
            file_id=file_id,
            status='COMPLETED'
        )

        # 6. Delete task from queue
        await self.queue.delete_task(task_id)

        self.logger.info(f"Task {task_id} completed successfully")
        return True

    except Exception as e:
        self.logger.error(f"Task {task_id} failed: {e}", exc_info=True)

        # Save error result
        await self.queue.save_result(
            task_id=task_id,
            file_id='',
            status='FAILED',
            error=str(e)
        )

        return False
```

---

## ✅ Микро-шаг 21: Main - loop обработки очереди (20 мин)

### Задача:
Основной event loop для слушания очереди

### Код:
```python
async def run(self):
    """Main event loop"""
    self.logger.info("Starting event loop...")

    try:
        while True:
            try:
                # Read tasks from queue
                tasks = await self.queue.read_tasks(count=5)

                if not tasks:
                    self.logger.debug("No tasks in queue, waiting...")
                    await asyncio.sleep(2)  # Wait before next check
                    continue

                # Process tasks concurrently
                self.logger.info(f"Processing {len(tasks)} tasks...")

                await asyncio.gather(
                    *[self.process_task(task) for task in tasks],
                    return_exceptions=True
                )

            except Exception as e:
                self.logger.error(f"Error in main loop: {e}", exc_info=True)
                await asyncio.sleep(5)  # Wait before retry

    except KeyboardInterrupt:
        self.logger.info("Interrupted by user")

    finally:
        await self.cleanup()
```

---

## ✅ Микро-шаг 22: Main - entry point (10 мин)

### Задача:
Функция main() для запуска Worker'а

### Код:
```python
async def main():
    """Entry point"""
    logger.info("=" * 50)
    logger.info("Telegram Export Worker Starting")
    logger.info("=" * 50)

    worker = ExportWorker()
    await worker.initialize()
    await worker.run()

if __name__ == '__main__':
    asyncio.run(main())
```

---

## ✅ Микро-шаг 23: Написать тесты - test_models.py (20 мин)

### Задача:
Unit тесты для моделей

### Код:
```python
import pytest
from models import ExportTask, ExportResult
from datetime import datetime

def test_export_task_validation():
    """Test ExportTask model validation"""
    task = ExportTask(
        task_id='123',
        user_id=456,
        chat_id=-1001234567890
    )
    assert task.task_id == '123'
    assert task.user_id == 456

def test_export_result_creation():
    """Test ExportResult model"""
    result = ExportResult(
        task_id='123',
        file_id='uuid-123',
        status='COMPLETED'
    )
    assert result.status == 'COMPLETED'
```

---

## ✅ Микро-шаг 24: Написать тесты - test_json_converter.py (25 мин)

### Задача:
Unit тесты для JSONConverter

### Код:
```python
import pytest
from json_converter import JSONConverter
from unittest.mock import Mock
from datetime import datetime

@pytest.fixture
def converter():
    return JSONConverter()

def test_convert_simple_message(converter):
    """Test converting simple message"""
    msg = Mock()
    msg.id = 123
    msg.date = datetime.now()
    msg.text = "Hello"
    msg.from_user = None
    msg.entities = []
    msg.media = None
    msg.forward_from = None

    result = converter.convert_message(msg)

    assert result['id'] == 123
    assert result['text'] == "Hello"
    assert 'date' in result

def test_finalize_json(converter):
    """Test finalizing JSON structure"""
    messages = []
    chat_info = {'id': 123, 'title': 'Test Chat'}

    result = converter.finalize_json(messages, chat_info)

    assert 'about' in result
    assert 'chats' in result
    assert 'messages' in result
```

---

## ✅ Микро-шаг 25: Написать тесты - test_queue_consumer.py (20 мин)

### Задача:
Unit тесты для QueueConsumer (с mocks)

### Код:
```python
import pytest
from queue_consumer import QueueConsumer
from unittest.mock import AsyncMock, patch

@pytest.mark.asyncio
async def test_connect_to_redis():
    """Test Redis connection"""
    consumer = QueueConsumer()

    with patch('aioredis.create_redis_pool') as mock_create:
        mock_create.return_value = AsyncMock()
        await consumer.connect()

        mock_create.assert_called_once()

@pytest.mark.asyncio
async def test_save_result():
    """Test saving result"""
    consumer = QueueConsumer()
    consumer.redis = AsyncMock()

    await consumer.save_result('task1', 'file1', 'COMPLETED')

    consumer.redis.xadd.assert_called_once()
```

---

## ✅ Микро-шаг 26: Тестирование config.py (10 мин)

### Задача:
Проверить что конфиг загружается из .env

### Команды:
```bash
cd export-worker
python3 -c "from config import settings; print(f'API_ID: {settings.telegram_api_id}')"
```

### Проверка успеха:
```
API_ID: 38317199
```

---

## ✅ Микро-шаг 27: Тестирование Pyrogram подключения (30 мин)

### Задача:
Подключиться к Telegram через Pyrogram с реальными credentials

### Тестовый скрипт:
```python
# export-worker/test_pyrogram_auth.py
import asyncio
from pyrogram_client import PyrogramClient

async def test():
    client = PyrogramClient()

    # Connect
    await client.authenticate()
    print("✓ Authentication successful")

    # Get user info
    me = await client.client.get_me()
    print(f"✓ Logged in as: {me.first_name}")

    # Disconnect
    await client.disconnect()
    print("✓ Disconnected")

if __name__ == '__main__':
    asyncio.run(test())
```

### Команды:
```bash
cd export-worker
python3 test_pyrogram_auth.py
# Нужно ввести SMS код когда будет запрос
```

### Проверка успеха:
```
Authentication successful
Logged in as: [Your Name]
Disconnected
```

---

## ✅ Микро-шаг 28: Тестирование Redis подключения (15 мин)

### Задача:
Проверить подключение к Redis

### Тестовый скрипт:
```python
# export-worker/test_redis_connection.py
import asyncio
from queue_consumer import QueueConsumer

async def test():
    consumer = QueueConsumer()

    # Connect
    await consumer.connect()
    print("✓ Connected to Redis")

    # Test write/read
    await consumer.redis.ping()
    print("✓ Ping successful")

    # Disconnect
    await consumer.disconnect()
    print("✓ Disconnected")

if __name__ == '__main__':
    asyncio.run(test())
```

### Команды:
```bash
# Убедиться что Redis запущен
docker-compose up redis -d

# Запустить тест
python3 export-worker/test_redis_connection.py
```

### Проверка успеха:
```
Connected to Redis
Ping successful
Disconnected
```

---

## ✅ Микро-шаг 29: Интеграционный тест - E2E (30 мин)

### Задача:
Полный flow от очереди до Java сервиса

### Скрипт:
```python
# export-worker/test_e2e.py
import asyncio
from export_worker import ExportWorker

async def test_e2e():
    worker = ExportWorker()

    # Initialize
    await worker.initialize()
    print("✓ Initialization successful")

    # Create test task
    test_task = {
        'id': '1-0',
        'user_id': 12345,
        'chat_id': -1001234567890,  # Public channel
        'date_from': None,
        'date_to': None
    }

    # Process task
    result = await worker.process_task(test_task)

    if result:
        print("✓ Task processed successfully")
    else:
        print("✗ Task processing failed")

    # Cleanup
    await worker.cleanup()

if __name__ == '__main__':
    asyncio.run(test_e2e())
```

---

## ✅ Микро-шаг 30: Сборка Docker образа (15 мин)

### Команды:
```bash
cd export-worker

# Build image
docker build -f Dockerfile -t export-worker:latest .

# Check image
docker image ls | grep export-worker

# Run test container (interactive)
docker run -it --rm \
  -e TELEGRAM_API_ID=38317199 \
  -e TELEGRAM_API_HASH=b6525f30c19d62deaf5b6a5bcbafaf9d \
  --network host \
  export-worker:latest python3 -c "from config import settings; print('OK')"
```

### Проверка успеха:
```
Successfully built abc123def456
OK
```

---

## ✅ Микро-шаг 31: Docker Compose интеграция (15 мин)

### Задача:
Убедиться что python-worker сервис в docker-compose.yml работает

### Проверка:
```bash
docker-compose up python-worker -d
docker-compose logs -f python-worker

# Должны увидеть логи Worker'а
```

---

## ✅ Микро-шаг 32: Финальная проверка и коммит (20 мин)

### Чеклист перед коммитом:

- [ ] Все файлы созданы
- [ ] requirements.txt заполнен
- [ ] Все импорты работают
- [ ] Config загружается из .env
- [ ] Pyrogram подключается и аутентифицируется
- [ ] Redis подключение работает
- [ ] Тесты написаны и проходят
- [ ] Dockerfile собирается
- [ ] Docker image запускается
- [ ] Нет hardcoded credentials
- [ ] Логирование работает
- [ ] Обработка ошибок на месте

### Финальные команды:
```bash
# Прогнать все тесты
pytest export-worker/tests/ -v

# Проверить что нет ошибок импорта
python3 -m py_compile export-worker/*.py

# Финальный коммит
git add export-worker/
git commit -m "feat: Phase 2 complete - Python Export Worker with Pyrogram"
```

---

**Версия:** 1.0
**Статус:** ✅ Детальный план готов
**Дата:** 2026-03-18
