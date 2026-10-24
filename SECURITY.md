# Security policy

## Supported versions

Only the latest release receives fixes. The project is distributed as signed APK builds
published on the [releases page](https://github.com/Amatsuki-Ichika/IwaraM/releases).

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Instead use GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository ("Security" tab → "Report a vulnerability"), or contact the maintainers
privately.

Please include:

- the affected version,
- steps to reproduce,
- the impact you believe the issue has.

We aim to acknowledge reports within a few days.

## Verifying a release build

Every release APK is signed with the project's release certificate, and the same certificate
is used for every version so builds can be updated in place. To verify a downloaded APK:

```bash
apksigner verify --print-certs IwaraM-<version>-arm64-v8a.apk
```

Compare the printed SHA-256 digest with the fingerprint below. If they differ, the file did
not come from this project — do not install it.

Every release also ships a `.sha256` checksum file next to the APK; use it to check download
integrity (before worrying about signatures):

```bash
sha256sum -c IwaraM-<version>-arm64-v8a.apk.sha256
```

Current release certificate SHA-256 fingerprint:

```
F4:D7:8C:50:5D:FA:F5:27:5C:D0:A2:14:A0:E5:A7:68:74:C0:52:21:8A:83:93:E6:13:22:AE:54:BC:EF:75:F6
```

## Secrets

This repository never contains signing keys or passwords. The release keystore, its
passwords (`keystore.properties`) and local SDK paths (`local.properties`) are ignored by
git; see [CONTRIBUTING.md](CONTRIBUTING.md#signing) for how maintainers configure them.
