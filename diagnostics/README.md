# One UI 9 / Android 17 discovery diagnostics

This test build keeps the application ID `moe.shizuku.privileged.api`.

## Compatibility repair

Android 17 changed the return type of the internal `IPackageManager.getInstalledPackages`
method from `ParceledListSlice` to `PackageInfoList`. Android also changed the
`IUserManager.getUsers` signature back to a single boolean parameter.
The platform API changes are documented in [HiddenApi PR #11](https://github.com/RikkaW/HiddenApi/pull/11).
The older `getInstalledPackagesNoThrow` wrapper swallowed the resulting error and
reported an empty list. This build resolves the actual platform methods at runtime,
preserves the original exceptions, and uses the fix for discovery, client Binder
delivery, and importing existing runtime grants.

Clients that declare `moe.shizuku.manager.permission.API_V23` do not need the additional
`moe.shizuku.client.V3_SUPPORT` metadata to appear in the list. Listing a client does
not grant access. Restricted profiles are logged separately; accessible profiles
continue to be scanned. If all package queries fail, the manager receives an error
instead of a successful empty list.

## Device verification

The runtime log tag is `ShizukuDiscovery`. The running server prints the immutable
marker `android17-discovery-v2` at startup and on every application-list request.
Logging covers Android SDK/build, selected method signatures, user IDs, per-user
package counts, matching clients and exclusions, Binder replies, grant status,
and the resulting UI list. It does not transmit logs automatically.

After installing, stop and restart the Shizuku server, open app management, and
request access from a client such as aShell. Run `collect-shizuku-logs.ps1` with
ADB connected to save the relevant device information and logs in Downloads.
The report includes names of Shizuku client packages but no app content.

The build is tested for platform method resolution, flag handling, user signatures,
and preservation of failures. CI verifies the final APK package ID and signature.
It has not yet been verified on the affected Samsung device.

## Installation and signing

The APK is a debug test build, not an official RikkaApps release. Its SHA-256,
certificate details, package metadata and source commit are included as assets.
CI currently generates a debug signing key for each clean runner. An APK signed
with a different key requires uninstalling the previous build first; an ordinary
update should be attempted before uninstalling. Download and verify the new APK
before removing the installed app. A reinstall resets the manager's local data
and can require granting client access again.
