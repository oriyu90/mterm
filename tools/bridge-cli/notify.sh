#!/usr/bin/env sh
# notify.sh "title" "body"
set -eu
exec "$(dirname "$0")/bridge-cli.sh" notification.show title="${1:-mterm}" body="${2:-done}"
