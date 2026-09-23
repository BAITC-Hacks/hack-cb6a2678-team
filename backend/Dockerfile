# Сборка: Gradle wrapper + JDK 17 (toolchain проекта)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# Отдельный слой с зависимостями — пересобирается только при изменении build.gradle.kts
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test && cp build/libs/backend-0.0.1-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
# Адреса сервисов внутри docker compose и пути к данным, смонтированным из репозитория
ENV ML_BASE_URL=http://ml:8000 \
    OLLAMA_BASE_URL=http://ollama:11434 \
    WEATHER_CACHE_DIR=/data/weather-cache \
    CYCLE_STORE_DIR=/data/cycles \
    ML_SCADA_DIR=/ml-data/raw \
    ML_EVALUATION_DIR=/ml-outputs/training
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
