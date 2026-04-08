# Export Worker Container
# Telegram message export service using Pyrogram

FROM python:3.11-slim

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

# Create non-root user and necessary directories
RUN groupadd -r app && useradd -r -g app app && \
    mkdir -p session logs && chown -R app:app /app
USER app

# Set environment variables
ENV PYTHONUNBUFFERED=1
ENV LOG_LEVEL=INFO

# Health check
HEALTHCHECK --interval=15s --timeout=5s --retries=3 --start-period=10s \
    CMD pgrep -f 'python.*main.py' || exit 1

# Graceful shutdown
STOPSIGNAL SIGTERM

# Run worker
CMD ["python", "-u", "main.py"]
