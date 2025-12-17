#!/bin/bash

# Gradle wrapper bootstrap script
# Downloads and runs Gradle

GRADLE_VERSION="8.2"
GRADLE_HOME="${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_ZIP_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

# Create dirs
mkdir -p "${GRADLE_HOME}"

# Download if needed
if [ ! -d "${GRADLE_HOME}/gradle-${GRADLE_VERSION}" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    curl -L -o "/tmp/gradle-${GRADLE_VERSION}.zip" "${GRADLE_ZIP_URL}"
    unzip -q "/tmp/gradle-${GRADLE_VERSION}.zip" -d "${GRADLE_HOME}"
    rm "/tmp/gradle-${GRADLE_VERSION}.zip"
fi

# Run Gradle
export JAVA_HOME="${JAVA_HOME:-/home/sam/.local/share/android-build-tools/jdk-17}"
"${GRADLE_HOME}/gradle-${GRADLE_VERSION}/bin/gradle" "$@"
