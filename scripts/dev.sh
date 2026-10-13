#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
# Use an installed full JDK 21. This script does not install a launcher or modify
# any global Java, Minecraft, key-binding, account, or server settings.
if [ "$#" -eq 0 ]; then set -- runClient; fi
exec ./gradlew "$@" --console=plain
