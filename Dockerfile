# Conduit API runtime image. Build the app with `sbt api/stage` FIRST (the runner does this), then this image
# just copies the staged launcher into a JRE — no sbt-in-Docker, so the image build is fast and reliable.
# (Mirrors the estate's eclipse-temurin build / JRE runtime split; JDK 21 LTS — 19 was EOL.)
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY api/target/universal/stage /app
# Ship the committed source snapshots IN the image so a fresh deploy (AWS) self-seeds + ignites with no external
# fetch. Locally, docker-compose bind-mounts ./ingest over this with the live data; in prod this is the source.
COPY ingest /app/ingest

# API 8080, health/admin 9990, Prometheus 9464 (doc 00). DB/host wired via env in docker-compose.local.yml.
EXPOSE 8080 9990 9464
ENV HYPERVOLT_ENV=local
ENV INGEST_DIR=/app/ingest

ENTRYPOINT ["/app/bin/conduit-api"]
