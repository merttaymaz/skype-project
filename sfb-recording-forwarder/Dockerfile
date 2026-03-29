FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser
WORKDIR /app
COPY build/libs/sfb-recording-forwarder.jar app.jar

# Kayit dizinleri
RUN mkdir -p /mnt/sfb-recordings/processed /mnt/sfb-recordings/failed \
    && chown -R appuser:appuser /app /mnt/sfb-recordings

USER appuser
EXPOSE 8480

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8480/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
