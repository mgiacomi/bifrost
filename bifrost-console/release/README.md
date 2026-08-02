# Bifrost Console runtime package

This archive is self-contained for its named operating system and architecture. It needs no JVM, Node.js, npm, database, separate web server, or shared Bifrost application filesystem.

Run `bifrost-console --version` (`.\\bifrost-console.exe --version` on Windows) to verify the executable. Start with `bifrost-console --no-open-browser`; open the printed loopback pairing URL in a browser and connect to a supported Bifrost Spring Boot target with its application key. The Console supports only the target/API version coordinated with this package version.

Configuration defaults to the operating-system profile location and the disposable analysis workspace defaults to the operating-system application state/cache location. Use `--config FILE` and `--work-dir DIRECTORY` for isolated locations. Target keys and custom trust roots are process-local inputs: protect the key, use a verified `ca-bundle` when required, and never place credentials in URLs or workspace files.

On shutdown the current transient workspace is removed best-effort. To remove remaining state, first stop the Console, then delete only the profile and marked workspace directories you deliberately selected. See the repository `bifrost-console/README.md` for exact locations, configuration, security boundaries, and troubleshooting.

Release archives are named `bifrost-console-VERSION-windows-x86_64.zip`, `bifrost-console-VERSION-linux-x86_64.tar.gz`, and `bifrost-console-VERSION-macos-arm64.tar.gz`. Verify a downloaded archive against `SHA256SUMS` with `sha256sum -c SHA256SUMS` on POSIX systems, or on PowerShell compare `(Get-FileHash -Algorithm SHA256 .\\ARCHIVE).Hash.ToLowerInvariant()` with its entry in `SHA256SUMS`.
