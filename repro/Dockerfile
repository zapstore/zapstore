FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# ---- Pinned inputs (override via --build-arg) ----
ARG FLUTTER_VERSION=3.38.6
ARG FLUTTER_SHA256=a70e3b829f53acd013aae65995755db0f421457b5ab754afa5a344cd5ec4d8d5

ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258

ARG ANDROID_PLATFORM=36
ARG ANDROID_PLATFORM_COMPAT_34=34
ARG ANDROID_PLATFORM_COMPAT_35=35
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG ANDROID_BUILD_TOOLS_COMPAT=34.0.0
ARG ANDROID_NDK_VERSION=27.0.12077973

# ---- System deps ----
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    openjdk-17-jdk \
    python3 \
    unzip \
    xz-utils \
    zip \
 && rm -rf /var/lib/apt/lists/*

# ---- Android SDK (pinned cmdline-tools + pinned packages) ----
ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" && \
    curl -fsSL -o /tmp/cmdline-tools.zip "${CMDLINE_TOOLS_URL}" && \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - && \
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" && \
    mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null

RUN sdkmanager \
    "platform-tools" \
    "platforms;android-${ANDROID_PLATFORM_COMPAT_34}" \
    "platforms;android-${ANDROID_PLATFORM_COMPAT_35}" \
    "platforms;android-${ANDROID_PLATFORM}" \
    "build-tools;${ANDROID_BUILD_TOOLS_COMPAT}" \
    "build-tools;${ANDROID_BUILD_TOOLS}" \
    "ndk;${ANDROID_NDK_VERSION}"

# ---- Non-root user (Flutter should not run as root) ----
RUN useradd -m -s /bin/bash builder

# ---- Flutter SDK (pinned version + sha256) ----
ENV FLUTTER_ROOT=/opt/flutter
ENV PATH="${PATH}:${FLUTTER_ROOT}/bin:${FLUTTER_ROOT}/bin/cache/dart-sdk/bin"

RUN curl -fsSL -o /tmp/flutter.tar.xz "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_${FLUTTER_VERSION}-stable.tar.xz" && \
    echo "${FLUTTER_SHA256}  /tmp/flutter.tar.xz" | sha256sum -c - && \
    mkdir -p /opt && \
    tar -xJf /tmp/flutter.tar.xz -C /opt && \
    rm /tmp/flutter.tar.xz

# Ensure builder owns toolchains (avoid git \"dubious ownership\" and permission issues)
RUN chown -R builder:builder /opt/flutter /opt/android-sdk

# Writable workspace for the non-root user
RUN mkdir -p /work && chown -R builder:builder /work

# Pre-warm caches (still may download plugin/Gradle deps at build time).
USER builder
RUN git config --global --add safe.directory /opt/flutter && flutter --version && flutter precache --android && flutter --disable-analytics >/dev/null 2>&1 || true

WORKDIR /work
