# Android build toolchain for pocketshell: JDK 17, SDK 36, NDK r26d, Gradle 8.13,
# plus what cross-compiling mosh-client needs. Nothing gets installed on the host.
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl unzip git ca-certificates build-essential autoconf automake libtool pkg-config perl \
      python3 file \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.3.11579264
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/bin

RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/clt.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools" "ndk;26.3.11579264" >/dev/null

RUN curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip \
    && unzip -q /tmp/gradle.zip -d /opt && mv /opt/gradle-8.13 /opt/gradle && rm /tmp/gradle.zip

# Host protoc matching the protobuf version mosh-client is built against.
RUN curl -fsSL -o /tmp/protoc.zip https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-linux-x86_64.zip \
    && unzip -q /tmp/protoc.zip -d /opt/protoc && rm /tmp/protoc.zip
ENV PATH=$PATH:/opt/protoc/bin

WORKDIR /work
