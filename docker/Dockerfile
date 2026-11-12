FROM eclipse-temurin:21.0.13_9-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends git unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ARG UID=1000
ARG GID=1000
# The noble image ships user "ubuntu" (UID/GID 1000), which clashes with the usual host UID.
RUN userdel -r ubuntu 2>/dev/null || true \
 && (groupadd -g ${GID} dev 2>/dev/null || true) \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash dev \
 && mkdir -p /home/dev/.gradle \
 && chown -R ${UID}:${GID} /home/dev
USER ${UID}:${GID}
ENV GRADLE_USER_HOME=/home/dev/.gradle
WORKDIR /src
