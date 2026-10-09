#!/usr/bin/env bash
# build-rootfs.sh — Debian trixie arm64 minbase rootfs for mterm.
#
# Runs debootstrap INSIDE an arm64 Debian container (native, no QEMU) and
# produces a stripped rootfs.tar.gz in the output dir.
# Usage: distribution/rootfs/build-rootfs.sh [output-dir]
#
# The tarball is later signed with scripts/sign-rootfs.py and published to
# a GitHub Release; the app verifies SHA-256 + Ed25519 before extracting.
set -euo pipefail

OUT_DIR="${1:-$(cd "$(dirname "$0")" && pwd)/out}"
VERSION="${ROOTFS_VERSION:-13.7-r1}"
MIRROR="${DEBIAN_MIRROR:-http://deb.debian.org/debian}"
BASE_PKGS="zsh,ca-certificates,curl,wget,git,openssh-client,procps,less,sudo,locales,python3"

mkdir -p "$OUT_DIR"

# NOTE: OUT_DIR must live under $HOME (colima shares $HOME with the VM;
# /private/tmp-style paths are invisible on the host after the build).

echo "=== mterm rootfs build: trixie arm64 ($VERSION) -> $OUT_DIR ==="
docker run --rm --platform linux/arm64 \
    -v "$OUT_DIR:/out" \
    -e DEBIAN_FRONTEND=noninteractive \
    debian:trixie-slim sh -ec '
        apt-get update -qq
        apt-get install -y -qq debootstrap > /dev/null
        echo "--- debootstrap trixie minbase ---"
        debootstrap --variant=minbase \
            --include='"$BASE_PKGS"' \
            trixie /rootfs '"$MIRROR"'
        echo "--- strip machine identity ---"
        rm -f /rootfs/etc/machine-id /rootfs/var/lib/dbus/machine-id
        rm -f /rootfs/etc/ssh/ssh_host_*
        rm -rf /rootfs/var/cache/apt/archives/*.deb \
                /rootfs/var/cache/apt/archives/partial/* \
                /rootfs/var/log/* \
                /rootfs/tmp/* /rootfs/var/tmp/*
        echo "--- guest skeleton ---"
        mkdir -p /rootfs/home/user
        printf "nameserver 1.1.1.1\nnameserver 8.8.8.8\n" > /rootfs/etc/resolv.conf
        echo "--- inventory ---"
        # \$ escaping: the format must reach dpkg-query literally (dash would
        # otherwise expand the empty vars).
        chroot /rootfs dpkg-query -W "-f=\${Package} \${Version} \${Architecture}\n" | sort > /out/package-inventory.txt
        wc -l /out/package-inventory.txt
        echo "--- archive ---"
        tar -czf /out/debian-trixie-arm64.tar.gz -C /rootfs .
        ls -la /out/
    '

echo "=== sha256 ==="
shasum -a 256 "$OUT_DIR/debian-trixie-arm64.tar.gz"
echo "=== done. Next: scripts/sign-rootfs.py --manifest <manifest> --key <private.pem> --archive $OUT_DIR/debian-trixie-arm64.tar.gz ==="
