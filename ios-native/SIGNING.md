# Signed iOS builds

The `iOS signed IPA` GitHub Actions workflow runs manually on `main`. It imports
the signing identity into a temporary macOS keychain, uses the app identifier and
distribution method permitted by the provisioning profile, archives the app,
exports an IPA, and verifies the exported signature and embedded profile.

## GitHub Actions secrets

Configure these repository secrets, never source files or workflow inputs:

- `SPX_IOS_CERTIFICATE_BASE64`: base64 encoding of the P12, including its private key.
- `SPX_IOS_P12_PASSWORD`: the P12 export password.
- `SPX_IOS_PROFILE_BASE64`: base64 encoding of the provisioning profile.

Run **Actions > iOS signed IPA > Run workflow**. The optional bundle identifier
must match the profile; leave it empty to derive it from an explicit profile.
Artifacts contain the IPA, SHA-256 checksum, and a verification report. Artifacts
are retained for 14 days. No App Store Connect upload or public release occurs.

An Ad Hoc (`release-testing`) IPA installs only on devices registered in its
profile. Export success is not a real-device acceptance test. It does not prove
that a certificate will remain valid or unrevoked after the build.

## Local macOS build

Keep certificates in the ignored `ios-native/signing/` directory. Supply
`SPX_IOS_CERTIFICATE_PATH`, `SPX_IOS_PROFILE_PATH`, and `SPX_IOS_P12_PASSWORD`
through your environment, optionally `SPX_BUNDLE_IDENTIFIER` and a positive
integer `SPX_BUILD_NUMBER`, then run:

```bash
cd ios-native
bash scripts/build-signed-ipa-macos.sh
```

The generated project changes only the app target's Release signing settings;
the default project specification remains suitable for unsigned simulator builds.
Running `scripts/bootstrap-macos.sh` regenerates that default project. Temporary
keychains and provisioning files are cleaned up on normal completion or failure;
GitHub-hosted runners are disposable. Do not add the signing directory to Git.
