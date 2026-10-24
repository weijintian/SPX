"""Import an ephemeral Apple signing identity and export a verified IPA.

Credentials come from environment variables or explicitly named local files.
They are never included in reports, console output, or repository files.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile


ROOT = Path(__file__).resolve().parents[1]


class BuildFailure(Exception):
    pass


def run(label, args, *, capture=False):
    print(f"[signing] {label}", flush=True)
    result = subprocess.run(args, cwd=ROOT, capture_output=capture)
    if result.returncode:
        if capture and label in {"Decode provisioning profile", "Find signing identity"}:
            print("[signing] Apple signing tool rejected the signing material.", flush=True)
        raise BuildFailure(f"{label} failed (exit {result.returncode}).")
    return result.stdout if capture else b""


def run_with_retries(label, args, *, attempts=4, delay_seconds=8):
    """Retry transient dependency/network failures before aborting the build."""
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            return run(label, args)
        except BuildFailure as error:
            last_error = error
            if attempt >= attempts:
                raise
            wait = delay_seconds * attempt
            print(f"[signing] {label} failed (attempt {attempt}/{attempts}); retrying in {wait}s.", flush=True)
            time.sleep(wait)
    raise last_error


def profile_settings(profile, requested_bundle="", now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    expiration = profile["ExpirationDate"]
    if expiration.tzinfo is None:
        expiration = expiration.replace(tzinfo=dt.timezone.utc)
    if expiration <= now:
        raise BuildFailure("Provisioning profile has expired.")
    entitlements = profile.get("Entitlements", {})
    identifier = entitlements.get("application-identifier", "")
    prefix, separator, allowed_bundle = identifier.partition(".")
    teams = profile.get("TeamIdentifier", [])
    if not separator or not prefix or not teams or not allowed_bundle:
        raise BuildFailure("Provisioning profile has no valid app or team identifier.")
    bundle = requested_bundle or allowed_bundle
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", bundle):
        raise BuildFailure("Supply a concrete bundle identifier for this profile.")
    compatible = bundle == allowed_bundle
    if allowed_bundle.endswith("*"):
        compatible = bundle.startswith(allowed_bundle[:-1])
    if not compatible:
        raise BuildFailure("Requested bundle identifier does not match the profile.")
    devices = profile.get("ProvisionedDevices", [])
    if profile.get("ProvisionsAllDevices"):
        method = "enterprise"
    elif devices:
        method = "debugging" if entitlements.get("get-task-allow") else "release-testing"
    else:
        method = "app-store-connect"
    certificates = [hashlib.sha1(cert).hexdigest().upper()
                    for cert in profile.get("DeveloperCertificates", [])]
    if not certificates:
        raise BuildFailure("Provisioning profile has no signing certificates.")
    return {
        "bundleID": bundle, "teamID": teams[0], "prefix": prefix,
        "uuid": profile["UUID"], "name": profile["Name"], "method": method,
        "expiration": expiration.isoformat(), "deviceCount": len(devices),
        "debugAllowed": bool(entitlements.get("get-task-allow")),
        "certificateSHA1s": certificates,
        "pushEnvironment": entitlements.get("aps-environment", ""),
    }


def write_credential(target, env_name, file_name):
    encoded = os.environ.get(env_name, "")
    source = os.environ.get(file_name, "")
    if encoded:
        try:
            target.write_bytes(base64.b64decode("".join(encoded.split()), validate=True))
        except (ValueError, TypeError) as error:
            raise BuildFailure(f"{env_name} is not valid base64.") from error
    elif source:
        shutil.copyfile(Path(source).expanduser(), target)
    else:
        raise BuildFailure(f"Missing {env_name} or {file_name}.")
    target.chmod(0o600)


def cleanup(state):
    if not state:
        return
    profile = Path(state["profilePath"]) if state.get("profilePath") else None
    if profile:
        backup = Path(state["profileBackup"]) if state.get("profileBackup") else None
        if backup and backup.exists():
            shutil.copyfile(backup, profile)
        else:
            profile.unlink(missing_ok=True)
    if state.get("keychain"):
        subprocess.run(["security", "delete-keychain", state["keychain"]],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if state.get("oldKeychains"):
        subprocess.run(["security", "list-keychains", "-d", "user", "-s", *state["oldKeychains"]],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if state.get("generatedSpec"):
        Path(state["generatedSpec"]).unlink(missing_ok=True)
    if state.get("temporary"):
        shutil.rmtree(state["temporary"], ignore_errors=True)


def verify_ipa(ipa, settings, build_number):
    with zipfile.ZipFile(ipa) as package:
        entries = [name for name in package.namelist()
                   if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        if len(entries) != 1:
            raise BuildFailure("Exported IPA must contain exactly one application.")
        info = plistlib.loads(package.read(entries[0]))
        if info.get("CFBundleIdentifier") != settings["bundleID"]:
            raise BuildFailure("Exported IPA has the wrong bundle identifier.")
        if build_number and info.get("CFBundleVersion") != build_number:
            raise BuildFailure("Exported IPA has the wrong build number.")
        if info.get("SPXNetworkTrust") != "system-public-ip-v1":
            raise BuildFailure("Exported IPA does not contain the public HTTPS login fix.")
        if settings.get("pushEnvironment") and info.get("SPXPushEnvironment") != settings["pushEnvironment"]:
            raise BuildFailure("Exported IPA does not contain the expected push environment.")
        if "NSAppTransportSecurity" in info:
            raise BuildFailure("Exported IPA unexpectedly relaxes system transport security.")
        embedded = entries[0].removesuffix("Info.plist") + "embedded.mobileprovision"
        if embedded not in package.namelist():
            raise BuildFailure("Exported IPA has no embedded provisioning profile.")
    return info


def prepare_signing_info_plist(source, target, settings):
    """Materialize custom keys in the plist used by the signed target.

    Do not rely on INFOPLIST_KEY_* to insert an arbitrary application key
    while Xcode is also processing an explicit INFOPLIST_FILE.
    """
    info = plistlib.loads(source.read_bytes())
    environment = settings.get("pushEnvironment", "")
    if environment not in {"", "development", "production"}:
        raise BuildFailure("Provisioning profile has an unsupported push environment.")
    if environment:
        info["SPXPushEnvironment"] = environment
    else:
        info.pop("SPXPushEnvironment", None)
    target.write_bytes(plistlib.dumps(info))
    return target


def build():
    if sys.platform != "darwin":
        raise BuildFailure("This command requires macOS, Xcode, XcodeGen and CocoaPods.")
    for executable in ("xcodebuild", "xcodegen", "pod", "security", "codesign", "ditto"):
        if not shutil.which(executable):
            raise BuildFailure(f"Required tool is missing: {executable}")
    if "SPX_IOS_P12_PASSWORD" not in os.environ:
        raise BuildFailure("Missing SPX_IOS_P12_PASSWORD.")
    build_number = os.environ.get("SPX_BUILD_NUMBER", "")
    if build_number and not re.fullmatch(r"[1-9][0-9]*", build_number):
        raise BuildFailure("SPX_BUILD_NUMBER must be a positive integer.")
    os.umask(0o077)
    temporary = Path(tempfile.mkdtemp(prefix="spx-ios-signing-"))
    state = {"temporary": str(temporary)}
    try:
        certificate = temporary / "distribution.p12"
        profile_path = temporary / "application.mobileprovision"
        write_credential(certificate, "SPX_IOS_CERTIFICATE_BASE64", "SPX_IOS_CERTIFICATE_PATH")
        write_credential(profile_path, "SPX_IOS_PROFILE_BASE64", "SPX_IOS_PROFILE_PATH")
        profile = plistlib.loads(run("Decode provisioning profile",
                                    ["security", "cms", "-D", "-i", str(profile_path)], capture=True))
        settings = profile_settings(profile, os.environ.get("SPX_BUNDLE_IDENTIFIER", ""))
        old_keychains = shlex.split(run("Read existing keychain search list",
                                       ["security", "list-keychains", "-d", "user"], capture=True).decode())
        state["oldKeychains"] = old_keychains
        keychain = temporary / "signing.keychain-db"
        state["keychain"] = str(keychain)
        keychain_password = secrets.token_urlsafe(32)
        run("Create temporary keychain", ["security", "create-keychain", "-p", keychain_password, str(keychain)])
        run("Configure temporary keychain", ["security", "set-keychain-settings", "-lut", "21600", str(keychain)])
        run("Unlock temporary keychain", ["security", "unlock-keychain", "-p", keychain_password, str(keychain)])
        run("Import signing identity", ["security", "import", str(certificate), "-P", os.environ["SPX_IOS_P12_PASSWORD"],
                                       "-T", "/usr/bin/codesign", "-T", "/usr/bin/security",
                                       "-t", "cert", "-f", "pkcs12", "-k", str(keychain)])
        run("Allow Apple signing tools", ["security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-s",
                                         "-k", keychain_password, str(keychain)])
        run("Activate signing keychain", ["security", "list-keychains", "-d", "user", "-s", str(keychain), *old_keychains])
        identities = run("Find signing identity", ["security", "find-identity", "-v", "-p", "codesigning", str(keychain)], capture=True).decode()
        identity = next((value for value in settings["certificateSHA1s"] if value in identities), None)
        if not identity:
            raise BuildFailure("No valid private-key identity matches the provisioning profile.")
        installed = Path.home() / "Library/MobileDevice/Provisioning Profiles" / (settings["uuid"] + ".mobileprovision")
        installed.parent.mkdir(parents=True, exist_ok=True)
        if installed.exists():
            backup = temporary / "previous.mobileprovision"
            shutil.copyfile(installed, backup)
            state["profileBackup"] = str(backup)
        state["profilePath"] = str(installed)
        shutil.copyfile(profile_path, installed)
        installed.chmod(0o600)
        release = {
            "PRODUCT_BUNDLE_IDENTIFIER": settings["bundleID"], "DEVELOPMENT_TEAM": settings["teamID"],
            "CODE_SIGN_STYLE": "Manual", "CODE_SIGN_IDENTITY": identity,
            "PROVISIONING_PROFILE_SPECIFIER": settings["name"],
            "OTHER_CODE_SIGN_FLAGS": "--keychain " + shlex.quote(str(keychain)),
        }
        signing_info = prepare_signing_info_plist(
            ROOT / "SpxChatIOS/Info.plist", temporary / "SpxChatIOS-Info.plist", settings)
        release["INFOPLIST_FILE"] = str(signing_info)
        if settings["pushEnvironment"] in {"development", "production"}:
            push_entitlements = temporary / "SpxChatIOS.entitlements"
            push_entitlements.write_bytes(plistlib.dumps({"aps-environment": settings["pushEnvironment"]}))
            release["CODE_SIGN_ENTITLEMENTS"] = str(push_entitlements)
        if build_number:
            release["CURRENT_PROJECT_VERSION"] = build_number
        spec = ROOT / "project.signing.generated.json"
        if spec.exists():
            raise BuildFailure("Remove a stale project.signing.generated.json before signing.")
        state["generatedSpec"] = str(spec)
        spec.write_text(json.dumps({"include": ["project.yml"], "targets": {
            "SpxChatIOS": {"settings": {"configs": {"Release": release}}}
        }}, indent=2), encoding="utf-8")
        run("Generate signing project", ["xcodegen", "generate", "--spec", str(spec)])
        run_with_retries("Resolve pinned dependencies", ["pod", "install"])
        archive = ROOT / "build/SpxChatIOS-signed.xcarchive"
        run("Archive signed application", ["xcodebuild", "-workspace", "SpxChatIOS.xcworkspace", "-scheme", "SpxChatIOS",
            "-configuration", "Release", "-destination", "generic/platform=iOS", "-derivedDataPath", "build/SignedDerivedData",
            "-archivePath", str(archive), "archive"])
        options = temporary / "ExportOptions.plist"
        options.write_bytes(plistlib.dumps({
            "method": settings["method"], "destination": "export", "teamID": settings["teamID"],
            "signingStyle": "manual", "signingCertificate": identity,
            "provisioningProfiles": {settings["bundleID"]: settings["uuid"]},
            "manageAppVersionAndBuildNumber": False, "stripSwiftSymbols": True,
        }))
        # Keep incomplete or unverified exports outside the published artifact
        # directory. Only a fully verified IPA is copied there below.
        export_staging = temporary / "export"
        export_staging.mkdir()
        run("Export signed IPA", ["xcodebuild", "-exportArchive", "-archivePath", str(archive),
            "-exportOptionsPlist", str(options), "-exportPath", str(export_staging)])
        ipas = list(export_staging.glob("*.ipa"))
        if len(ipas) != 1:
            raise BuildFailure("Expected exactly one exported IPA.")
        info = verify_ipa(ipas[0], settings, build_number)
        run("Extract IPA preserving executable permissions",
            ["ditto", "-x", "-k", str(ipas[0]), str(temporary / "verification")])
        app = next((temporary / "verification/Payload").glob("*.app"))
        run("Verify application and nested code signatures", ["codesign", "--verify", "--deep", "--strict", "--verbose=2", str(app)])
        embedded_profile = plistlib.loads(run("Decode exported provisioning profile",
            ["security", "cms", "-D", "-i", str(app / "embedded.mobileprovision")], capture=True))
        if embedded_profile.get("UUID") != settings["uuid"]:
            raise BuildFailure("Exported provisioning profile does not match the selected profile.")
        signed = run("Verify signed entitlements", ["codesign", "-d", "--entitlements", ":-", str(app)], capture=True)
        entitlements = plistlib.loads(signed[signed.index(b"<?xml"):])
        if entitlements.get("application-identifier") != settings["prefix"] + "." + settings["bundleID"]:
            raise BuildFailure("Exported signing application identifier is incorrect.")
        if bool(entitlements.get("get-task-allow")) != settings["debugAllowed"]:
            raise BuildFailure("Exported debugging entitlement does not match the profile.")
        if entitlements.get("aps-environment", "") != settings["pushEnvironment"]:
            raise BuildFailure("Exported push entitlement does not match the profile.")
        name = f"SPX-{info['CFBundleShortVersionString']}-{info['CFBundleVersion']}.ipa"
        output = ROOT / "build/export"
        output.mkdir(parents=True, exist_ok=True)
        target = output / name
        if target.exists():
            raise BuildFailure("An IPA with the target version already exists; use a new build number.")
        shutil.copyfile(ipas[0], target)
        with target.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        (output / "SHA256SUMS.txt").write_text(f"{digest}  {target.name}\n", encoding="utf-8")
        report = {"status": "success", "file": name, "bytes": target.stat().st_size, "sha256": digest,
            "bundleID": settings["bundleID"], "version": info["CFBundleShortVersionString"],
            "build": info["CFBundleVersion"], "method": settings["method"], "teamID": settings["teamID"],
            "profileExpires": settings["expiration"], "registeredDevices": settings["deviceCount"],
            "signatureVerified": True, "networkTrust": info.get("SPXNetworkTrust"),
            "sourceCommit": os.environ.get("GITHUB_SHA") or os.environ.get("CM_COMMIT", "local"),
            "realDeviceTesting": "not_run"}
        (output / "signing-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report), flush=True)
    finally:
        cleanup(state)


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        build()
        return 0
    except (BuildFailure, OSError, ValueError, plistlib.InvalidFileException) as error:
        print(f"[signing] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
