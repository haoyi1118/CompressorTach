#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
BASE="https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar"
if [ ! -f "$WRAPPER" ]; then
  command -v curl >/dev/null 2>&1 || { echo "Install curl first" >&2; exit 1; }
  echo "Downloading official Gradle wrapper..."
  curl -fLSs --retry 2 -o "$WRAPPER" "$BASE"
fi
if command -v curl >/dev/null 2>&1 && command -v sha256sum >/dev/null 2>&1; then
  EXPECTED=$(curl -fLSs --retry 2 "$BASE.sha256" | tr -d '\r\n' | cut -d' ' -f1)
  ACTUAL=$(sha256sum "$WRAPPER" | cut -d' ' -f1)
  [ "$ACTUAL" = "$EXPECTED" ] || { rm -f "$WRAPPER"; echo "Wrapper checksum mismatch" >&2; exit 1; }
fi
exec java -Xmx128m -cp "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"
