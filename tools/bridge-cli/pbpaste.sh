#!/usr/bin/env sh
# pbpaste — foreground-only on Android 10+; permission-denied is normal when backgrounded.
set -eu
exec "$(dirname "$0")/bridge-cli.sh" clipboard.paste
