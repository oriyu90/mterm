# Licenses — mterm

- App code: MIT (see ../../LICENSE). Author: Yuki_Orita.
- `core/terminal-emulator`: clean-room minimal emulator written for this project (Apache-2.0 licensed, header in each file). It does NOT copy Termux GPLv3 app code. Only the Apache-2.0-exception concept (terminal-view/terminal-emulator subset) is followed; no Termux sources are vendored in this MVP.
- Termux references [R10]: https://github.com/termux/termux-app/blob/master/LICENSE.md — terminal-view/terminal-emulator are Apache-2.0 exception; GPLv3 app code is NOT mixed in.
- PRoot: GPL-licensed executable (termux/proot fork family). The PRoot binary itself is NOT committed here; it is version-pinned with the app release and its source/notice must be provided separately at distribution time (see docs/IMPLEMENTATION_PLAN.md RISK-09). Do not replace PRoot/bridge-cli binaries from inside the rootfs.
- libsu: not bundled in this MVP (pure `su` detection via `Runtime.exec` to avoid JitPack). If libsu 6.x is adopted later, pin the tag and keep `app-remote` free of root deps.
- Debian rootfs: Debian Free Software Guidelines; per-release SBOM + package inventory are stored under `distribution/sbom/`.
