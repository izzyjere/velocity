# Releasing Velocity

Push a semantic version tag such as `v1.0.0`. The release workflow derives Android `versionName` and `versionCode`, runs the unit tests and lint, builds a minified signed APK, verifies its certificate, and publishes:

```text
velocity-{version}.apk
velocity-{version}.apk.sha256
```

The workflow requires these repository secrets:

- `VELOCITY_KEYSTORE_BASE64`
- `VELOCITY_SIGNING_STORE_PASSWORD`
- `VELOCITY_SIGNING_KEY_ALIAS`
- `VELOCITY_SIGNING_KEY_PASSWORD`

The production signing certificate has SHA-256 fingerprint:

```text
C4:B2:B3:8C:C3:94:78:68:53:21:25:62:88:C2:E5:AD:F5:41:F6:8B:52:26:BF:D3:47:69:4B:3D:34:2F:80:83
```

The private keystore is intentionally excluded from Git. Back up the `.signing/velocity-release-2026.jks` file separately and protect it as a permanent release credential. Losing the signing key prevents publishing updates that Android accepts over an existing installation.
