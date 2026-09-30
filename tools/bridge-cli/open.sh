#!/usr/bin/env sh
# open guest path or URL via bridge (respects .. escape policy server-side)
set -eu
if [ $# -lt 1 ]; then echo "usage: open.sh <url-or-guest-path>" >&2; exit 64; fi
case "$1" in http://*|https://*) exec "$(dirname "$0")/bridge-cli.sh" open.url url="$1";; *) exec "$(dirname "$0")/bridge-cli.sh" open.path path="$1";; esac
