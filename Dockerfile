# Conduit API runtime image. Build the app with `sbt api/stage` FIRST (the runner does this), then this image
# just copies the staged launcher into a JRE — no sbt-in-Docker, so the image build is fast and reliable.
# (Mirrors the estate's eclipse-temurin:19-jdk build / 19-jre runtime split.)
FROM eclipse-temurin:19-jre

WORKDIR /app
COPY api/target/universal/stage /app

# API 8080, health/admin 9990, Prometheus 9464 (doc 00). DB/host wired via env in docker-compose.local.yml.
EXPOSE 8080 9990 9464
ENV HYPERVOLT_ENV=local

ENTRYPOINT ["/app/bin/conduit-api"]
