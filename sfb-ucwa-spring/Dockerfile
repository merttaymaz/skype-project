## Build stage
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./

# Dependency cache layer
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src/ src/

RUN ./gradlew bootJar --no-daemon -x test

## Runtime stage
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="databoss"
LABEL description="Skype for Business UCWA Spring Boot Application"

RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

# FFmpeg — audio donusum icin
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/build/libs/sfb-ucwa-spring.jar app.jar

RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8443

HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=40s \
    CMD curl -f http://localhost:8443/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
