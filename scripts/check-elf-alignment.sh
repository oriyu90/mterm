#!/usr/bin/env bash
# Check 16 KB page-size readiness for all .so (design R4).
# Pass: every loadable segment uses max-page-size 16384 alignment compatible layout.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="$HOME/Library/Android/sdk/ndk/28.2.13676358"
READELF="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
FAIL=0
count=0
check_so() {
  local so="$1"
  count=$((count+1))
  # LOAD segments alignment must divide 16384 (i.e. Align % 16384 == 0 or Align <= 16384 with 16384 % Align == 0)
  local aligns
  aligns="$("$READELF" -l "$so" 2>/dev/null | awk '$1=="LOAD" {print $NF}' || true)"
  if [ -z "$aligns" ]; then echo "WARN: no LOAD segs: $so"; return; fi
  while IFS= read -r a; do
    # normalize hex
    local dec=$((a))
    if [ $((16384 % dec)) -ne 0 ] && [ $((dec % 16384)) -ne 0 ]; then
      echo "FAIL: $so align $a not 16KB-compatible"; FAIL=1
    fi
  done <<< "$aligns"
  echo "OK: $so"
}
if [ ! -x "$READELF" ]; then echo "llvm-readelf not found at $READELF"; exit 2; fi
while IFS= read -r -d '' so; do check_so "$so"; done < <(find "$ROOT" -name '*.so' -path '*arm64-v8a*' -print0 2>/dev/null)
echo "checked=$count fail=$FAIL"
exit $FAIL
