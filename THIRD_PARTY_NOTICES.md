# Third-Party Notices

XR-Editor bundles or builds the following open-source components into its Android APK:

## 1. OpenVSCode Server
- **Upstream:** https://github.com/gitpod-io/openvscode-server
- **License:** MIT License
- **Version:** `openvscode-server-v1.109.5` (`linux-arm64`)

## 2. PRoot (Termux fork)
- **Upstream:** https://github.com/termux/proot
- **License:** GNU General Public License v2.0 (GPL-2.0)
- **Version:** `v5.1.107.102`
- **Build Script:** `scripts/build-proot-android.sh`
- Compiled from unmodified upstream source for Android `arm64-v8a` (`libproot.so`, `libproot-loader.so`).

## 3. Samba talloc
- **Upstream:** https://talloc.samba.org/
- **License:** GNU Lesser General Public License v3.0 or later (LGPL-3.0-or-later)
- **Version:** `2.5.0`
- Statically linked into `libproot.so` via `scripts/build-proot-android.sh`.

## 4. Debian GNU/Linux (bookworm-slim, arm64)
- **Upstream:** https://www.debian.org/
- **License:** Multiple free software licenses (see `/usr/share/doc/*/copyright` inside the bundled rootfs).

## 5. Apache Commons Compress
- **Upstream:** https://commons.apache.org/proper/commons-compress/
- **License:** Apache License 2.0
