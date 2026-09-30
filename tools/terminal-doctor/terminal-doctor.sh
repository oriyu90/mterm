#!/usr/bin/env sh
# terminal-doctor — on-device Gate A/B/C probes (informational; never auto-fixes mounts).
set -eu
echo "== mterm doctor =="
echo "abi: $(uname -m)"
echo "--- E2E suite: see scripts/e2e-commands.sh (run inside Debian) ---"
echo "Gate A: PRoot nested exec (bash->python subprocess, node child)"
echo "Gate B: npx vite serves localhost"
echo "Gate C (modern only): ExecBroker app-data ELF probe"
echo "Gate D: private mount namespace probe (unshare -m true)"
(unshare -m true 2>/dev/null && echo "mountNamespace: OK") || echo "mountNamespace: UNSUPPORTED (root chroot disabled)"
echo "done (paths/tokens redacted in export)"
