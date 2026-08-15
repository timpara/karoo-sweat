FROM ubuntu:24.04
# Runtime libraries the Android emulator needs when running headless.
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libpulse0 libnss3 libxcb1 libxcb-xinerama0 libxcb-cursor0 libx11-6 libxext6 \
      libxdamage1 libxfixes3 libxcomposite1 libxrandr2 libgl1 libglu1-mesa libegl1 \
      libasound2t64 libdbus-1-3 libfontconfig1 libfreetype6 ca-certificates \
      procps net-tools socat \
  && rm -rf /var/lib/apt/lists/*
ENV ANDROID_SDK_ROOT=/sdk ANDROID_HOME=/sdk ANDROID_AVD_HOME=/avd
ENV PATH=/sdk/emulator:/sdk/platform-tools:/sdk/cmdline-tools/latest/bin:$PATH
WORKDIR /work
