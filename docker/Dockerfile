FROM eclipse-temurin:24.0.2_12-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends git unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ARG UID=1000
ARG GID=1000
RUN groupadd -g ${GID} dev 2>/dev/null || true \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash dev 2>/dev/null || true
USER ${UID}:${GID}
ENV GRADLE_USER_HOME=/home/dev/.gradle
WORKDIR /src
