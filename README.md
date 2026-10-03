# Velocity Download Manager

Velocity is a Java-only Android download manager for Android 10+ (API 29). It uses native Views and Material 3, Room-backed recovery, a secured WebView browser, user-consented share/open-with interception, and an adaptive multipart engine optimized for useful end-to-end throughput.


## Releases


**Current release: v0.0.3**

[Download velocity-0.0.3.apk](https://github.com/izzyjere/velocity/releases/download/v0.0.3/velocity-0.0.3.apk)

Velocity can also check and install updates from **More → Check for updates**. The updater accepts only this repository's versioned release assets, verifies GitHub and published SHA-256 digests, checks package identity and signing-certificate continuity, and then uses Android's user-confirmed package installer.

Maintainer instructions and the signing-certificate fingerprint are documented in [docs/RELEASING.md](docs/RELEASING.md).

## Engine design

The transfer path is isolated from Activities and Fragments:

```text
UI -> DownloadRepository -> DownloadCoordinator -> DownloadJob
                                              |-> DownloadPlanner
                                              |-> AdaptiveParallelism
                                              |-> Cronet / OkHttp
                                              |-> PositionedFileWriter
                                              `-> IntegrityVerifier
```

- Cronet is preferred for HTTP/2 and HTTP/3/QUIC, with a process-wide engine and an OkHttp compatibility fallback.
- A real `bytes=0-0` request verifies range behavior before multipart mode is selected.
- Auto mode starts conservatively, schedules small durable ranges onto a bounded shared worker pool, scales only after measured gains, and backs off on weak samples or throttling.
- Workers write directly to absolute offsets in one preallocated `.part` file. No complete response or part file is buffered in memory.
- Segment offsets, validators, retry state, queue position, and smoothed progress are persisted through Room. Progress writes are limited to once per second and presentation updates are independently throttled.
- Resume requests use `If-Range`, validate exact inclusive `Content-Range` boundaries, and reject changed or range-ignoring resources rather than joining unrelated bytes.
- Completion requires complete segment coverage, a matching file size, an optional checksum match, a durable flush, and an atomic final move.

## Android execution

- Android 14+ uses user-initiated data transfer jobs with visible notifications and network constraints.
- Older supported versions use a `dataSync` foreground service started directly from a visible user action.
- The service implements Android 15 timeout handling. Boot recovery uses WorkManager only to reconcile metadata; it never launches a prohibited `dataSync` foreground service from `BOOT_COMPLETED`.
- Connectivity changes cancel active calls at safe checkpoints. Wi-Fi-only transfers pause before continuing on a metered network.

## Security

- TLS and hostname verification are never bypassed; HTTPS downgrade redirects are rejected.
- Authorization and Cookie headers are stripped on cross-origin redirects.
- Browser cookies and request headers required for resume are encrypted with an Android Keystore AES-GCM key.
- WebView file/content access and mixed content are disabled, Safe Browsing is enabled, and no JavaScript bridge is exposed.
- Filenames and canonical destinations are validated against traversal, reserved names, control characters, and collisions.
- Active transfers use app-private temporary storage for safe multipart/resume writes. Verified downloads are published to `Downloads/Velocity` by default and organized into Music, Videos, Documents, Compressed, Images, Apps, and Other folders. The root destination can be changed from **More → Download location**.

## Build and verification

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat compileDebugAndroidTestJavaWithJavac
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug assembleRelease
```

The deterministic HTTP suite covers byte-range partitioning, adaptive scaling, retry classification, sanitization, content-disposition parsing, exact positioned writes, ignored and malformed ranges, redirect secret stripping, and SHA-256 output equivalence. Android tests cover Room progress/cascade behavior and the non-destructive schema migration.

The included local benchmark uses an 8 MiB deterministic payload and a range server throttled per connection. A representative run on the development machine measured 18.51 MiB/s for one stream and 62.21 MiB/s for four ranges (3.36x), with identical SHA-256 output. This validates engine behavior under per-connection throttling; real-device Wi-Fi/cellular results must be measured separately because emulator or loopback results do not predict radio and storage performance.
