FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app
COPY pom.xml .
# КРИТИЧНО: копируем конфиг стиля, иначе mvn package упадет
COPY checkstyle.xml . 
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -g 1000 appgroup && adduser -u 1000 -G appgroup -D appuser

COPY --from=builder /app/target/telegram-cleaner-1.0.0.jar app.jar

VOLUME /data/import
VOLUME /data/export

USER appuser

EXPOSE 8080

# Обновляем путь с учетом /tcleaner, чтобы докер видел, что сервис живой
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/tcleaner/api/health || exit 1

ENTRYPOINT ["java", "-Xmx256m", "-Xms64m", "-jar", "app.jar"]