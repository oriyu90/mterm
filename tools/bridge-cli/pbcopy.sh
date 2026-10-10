#!/usr/bin/env sh
# pbcopy — copy stdin to the Android clipboard via bridge.
# Usage: echo hello | pbcopy.sh
set -eu
TEXT="$(cat)"
exec "$(dirname "$0")/bridge-cli.sh" clipboard.copy text="$TEXT"
