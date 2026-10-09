#!/usr/bin/env python3
"""Sign a Debian rootfs manifest with the Ed25519 release key.

Canonical bytes MUST match ManifestVerifier.canonicalBytes(): a JSON object
with keys in this exact order, excluding "signature":
  arch, createdAt, id, minAppVersion, schema, sha256, size, version

Usage:
  scripts/sign-rootfs.py --manifest distribution/manifests/debian-trixie-arm64.json
      --key ~/path/to/mterm-rootfs-ed25519.private.pem   # or .b64 raw seed
      [--archive path/to/rootfs.tar.zst]                 # refresh sha256/size

The private key lives in the private common-rules-document repository
(keystores/mterm-rootfs-ed25519.private.pem) and is never committed to mterm.
Requires: pip install cryptography
"""
import argparse
import base64
import hashlib
import json
import sys
from collections import OrderedDict

CANONICAL_KEYS = ["arch", "createdAt", "id", "minAppVersion", "schema", "sha256", "size", "version"]


def load_private_key(path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    raw = open(path, "rb").read()
    if b"BEGIN" in raw:
        return serialization.load_pem_private_key(raw, password=None)
    # raw 32-byte seed, base64-encoded
    seed = base64.b64decode(raw.decode().strip())
    if len(seed) != 32:
        raise ValueError("raw seed must be 32 bytes")
    return Ed25519PrivateKey.from_private_bytes(seed)


def load_rsa_key(path):
    from cryptography.hazmat.primitives import serialization
    raw = open(path, "rb").read()
    return serialization.load_pem_private_key(raw, password=None)


def canonical_bytes(manifest):
    obj = OrderedDict((k, manifest[k]) for k in CANONICAL_KEYS)
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--rsa-key", default=None,
                    help="RSA-2048 PKCS8 PEM for the pre-33 signatureRsa field")
    ap.add_argument("--archive", default=None)
    args = ap.parse_args()

    with open(args.manifest, encoding="utf-8") as f:
        manifest = json.load(f)

    if args.archive:
        h = hashlib.sha256()
        size = 0
        with open(args.archive, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                h.update(chunk)
                size += len(chunk)
        manifest["sha256"] = h.hexdigest()
        manifest["size"] = size
        print(f"sha256={manifest['sha256']} size={size}")

    missing = [k for k in CANONICAL_KEYS if k not in manifest]
    if missing:
        print(f"ERROR: manifest missing keys: {missing}", file=sys.stderr)
        return 1

    key = load_private_key(args.key)
    sig = key.sign(canonical_bytes(manifest))
    manifest["signature"] = "ed25519:" + base64.b64encode(sig).decode()

    if args.rsa_key:
        from cryptography.hazmat.primitives.asymmetric.padding import PKCS1v15
        from cryptography.hazmat.primitives.hashes import SHA256
        rsa_key = load_rsa_key(args.rsa_key)
        rsa_sig = rsa_key.sign(canonical_bytes(manifest), PKCS1v15(), SHA256())
        manifest["signatureRsa"] = "rsa:" + base64.b64encode(rsa_sig).decode()
        print("rsa-signed:", args.manifest)

    with open(args.manifest, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("signed:", args.manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
