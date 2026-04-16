# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml checkstyle.xml ./
# Dependencies in separate layer (cached on rebuild)
RUN mvn dependency:go-offline -q
COPY src/ ./src/
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
# LABEL нужен для фильтрованного docker prune в CI/CD — чтобы не снести образы
# чужих проектов, живущих на том же хосте (external-service и др).
LABEL project=telegram-cleaner
WORKDIR /app
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app && \
    mkdir -p /data/import /data/export && chown -R app:app /data
COPY --from=build --chown=app:app /app/target/telegram-cleaner-*.jar app.jar
USER app

HEALTHCHECK --interval=10s --timeout=5s --retries=5 --start-period=30s \
    CMD curl -sf http://localhost:8080/api/health || exit 1

# Явно ограничиваем heap под сервер 3 ГБ (mem_limit контейнера — 768m).
# -Xmx640m оставляет ~128m для JVM native memory, metaspace и стека потоков.
ENV JAVA_OPTS="-Xms256m -Xmx640m -XX:+UseG1GC"

EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
