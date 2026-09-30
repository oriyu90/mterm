#!/usr/bin/env bash
# E2E command suite (design 16.3) — run INSIDE Debian (PRoot/chroot), set -e.
set -e
git --version
ssh -V
python3 --version
python3 -c 'import subprocess; subprocess.check_call(["/bin/echo","ok"])'
node --version || echo "node: install web preset first"
npm --version || true
npx --yes vite --version || echo "vite: network required"
gcc --version || clang --version || echo "toolchain: install native preset"
cmake --version || true
tmux -V || true
echo "E2E base OK"
