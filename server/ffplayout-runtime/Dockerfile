FROM debian:trixie-20260824-slim

ARG FFPLAYOUT_VERSION=2.2.1
ARG TARGETARCH

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    ffmpeg \
    sqlite3 \
    libsqlite3-0 \
    libasound2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/ffplayout

RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) asset="ffplayout-v${FFPLAYOUT_VERSION}_server_x86_64-unknown-linux-gnu.tar.gz" ;; \
      arm64) asset="ffplayout-v${FFPLAYOUT_VERSION}_server_aarch64-unknown-linux-gnu.tar.gz" ;; \
      *) echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl --fail --show-error --location --retry 5 \
      "https://github.com/ffplayout/ffplayout/releases/download/v${FFPLAYOUT_VERSION}/${asset}" \
      -o /tmp/ffplayout.tar.gz; \
    tar -xzf /tmp/ffplayout.tar.gz --strip-components=1; \
    test -x /opt/ffplayout/ffplayout; \
    rm -f /tmp/ffplayout.tar.gz

RUN useradd --system --create-home --home-dir /var/lib/ffplayout --shell /usr/sbin/nologin ffpu && \
    mkdir -p /config /media /var/log/ffplayout /var/lib/ffplayout && \
    chown -R ffpu:ffpu /opt/ffplayout /config /media /var/log/ffplayout /var/lib/ffplayout

USER ffpu

EXPOSE 8787

VOLUME ["/config", "/media", "/var/log/ffplayout"]

ENTRYPOINT ["/opt/ffplayout/ffplayout"]
CMD ["--listen", "0.0.0.0:8787"]
