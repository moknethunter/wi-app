#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
version=8.11.1
if [[ -n "${GRADLE_HOME:-}" && -x "$GRADLE_HOME/bin/gradle" ]]; then
    exec "$GRADLE_HOME/bin/gradle" "$@"
fi
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/wikall"
install="$cache/gradle-$version"
if [[ ! -x "$install/bin/gradle" ]]; then
    mkdir -p "$cache"
    staging="$(mktemp -d "$cache/download.XXXXXX")"
    trap 'rm -rf "$staging"' EXIT
    curl -fsSL "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$staging/gradle.zip"
    checksum="$(cat tools/gradle-8.11.1.sha256)"
    printf '%s  %s\n' "$checksum" "$staging/gradle.zip" | sha256sum --check -
    unzip -q "$staging/gradle.zip" -d "$staging"
    if [[ ! -e "$install" ]]; then
        mv -T "$staging/gradle-$version" "$install"
    fi
fi
exec "$install/bin/gradle" "$@"
