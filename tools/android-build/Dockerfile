# Optional: bake the SDK packages into a local image so repeated builds skip
# sdkmanager. The ready-made base image supplies JDK + cmdline-tools.
#
#   docker build -f tools/android-build/Dockerfile -t mercury-android-build .
#   bash tools/android-build/build-apk.sh mercury-android-build
#
# Requires network for the base image and for the SDK packages.
FROM thyrlian/android-sdk:latest

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk

RUN yes | sdkmanager --licenses >/dev/null 2>&1 || true \
 && sdkmanager "platform-tools" "platforms;android-37.1" "build-tools;37.0.0" \
 && java -version 2>&1 | head -1

WORKDIR /work