#!/usr/bin/env sh
# mterm bridge-cli (Linux guest side) — talks to AndroidBridgeServer over UDS.
# Usage: bridge-cli.sh <method> [key=value ...]
# Methods: open.url open.path clipboard.copy clipboard.paste notification.show app.info
# Protocol: 4-byte big-endian length + JSON {v:1,id,method,params}. Socket: $MTERM_BRIDGE_SOCK or /run/android-bridge/bridge.sock
set -eu
SOCK="${MTERM_BRIDGE_SOCK:-/run/android-bridge/bridge.sock}"
METHOD="${1:-app.info}"; shift || true
ID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo $$)"
PARAMS="{"
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  v_esc="$(printf '%s' "$v" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  PARAMS="$PARAMS\"$k\":\"$v_esc\","
done
PARAMS="$(printf '%s' "$PARAMS" | sed 's/,$//')}"
JSON="{\"v\":1,\"id\":\"$ID\",\"method\":\"$METHOD\",\"params\":{$PARAMS}}"
if [ ! -S "$SOCK" ]; then echo "bridge unavailable: $SOCK" >&2; exit 69; fi
printf '%s' "$JSON" | python3 -c "
import json,os,socket,struct,sys
sock=os.environ.get('MTERM_BRIDGE_SOCK','/run/android-bridge/bridge.sock')
data=sys.stdin.read().encode()
s=socket.socket(socket.AF_UNIX); s.connect(sock)
s.sendall(struct.pack('>I',len(data))+data)
hdr=s.recv(4)
if len(hdr)<4: sys.exit('short reply')
n=struct.unpack('>I',hdr)[0]
if n>1048576: sys.exit('oversized reply')
buf=b''
while len(buf)<n:
  c=s.recv(n-len(buf))
  if not c: break
  buf+=c
print(buf.decode())
"
