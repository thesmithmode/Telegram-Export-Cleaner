# Export Worker Container
# Telegram message export service using Pyrogram

FROM python:3.11-slim

# LABEL нужен для фильтрованного docker prune в CI/CD — чтобы не снести образы
# других проектов на том же хосте.
LABEL project=telegram-cleaner

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    procps \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Create non-root user and necessary directories (including SQLite cache dir)
RUN groupadd -r -g 1001 app && useradd -r -u 1001 -g app app && \
    mkdir -p session logs /data/cache && chown -R app:app /app /data
USER app

# Set environment variables
ENV PYTHONUNBUFFERED=1
ENV LOG_LEVEL=INFO

# Health check
HEALTHCHECK --interval=15s --timeout=5s --retries=3 --start-period=10s \
    CMD pid=$(pgrep -f 'python.*main.py' | head -1) && [ -n "$pid" ] && grep -qE "^State:[[:space:]]*[RS]" /proc/$pid/status || exit 1

# Graceful shutdown
STOPSIGNAL SIGTERM

# Run worker
CMD ["python", "-u", "main.py"]
