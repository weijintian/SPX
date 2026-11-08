#!/usr/bin/env python3
"""Guard that the iOS bundle identifier and team agree everywhere.

Values that must line up:
  ios-native/project.yml                 PRODUCT_BUNDLE_IDENTIFIER / DEVELOPMENT_TEAM
  codemagic.yaml                         SPX_BUNDLE_IDENTIFIER / SPX_SIGNING_MODE
  ios-native/signing/*.mobileprovision   application-identifier (manual signing only)
  Apple Developer portal                 the App ID registered for that bundle id

Profiles under signing/legacy-*/ are archived material from another team: they are
reported but never compared. Exit status is 1 only when SPX_REQUIRE_SIGNING_MATCH=1.
"""
import os
import plistlib
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve()
IOS = HERE.parents[1]          # ios-native/
REPO = IOS.parent

PROBLEMS = []
NOTES = []


def read_text(path):
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def project_values():
    text = read_text(IOS / "project.yml")
    bundle = re.search(r"PRODUCT_BUNDLE_IDENTIFIER:\s*([A-Za-z0-9.\-]+)", text)
    team = re.search(r"DEVELOPMENT_TEAM:\s*([A-Za-z0-9]+)", text)
    prefix = re.search(r"bundleIdPrefix:\s*([A-Za-z0-9.\-]+)", text)
    return (
        bundle.group(1) if bundle else "",
        team.group(1) if team else "",
        prefix.group(1) if prefix else "",
    )


def ci_values():
    text = read_text(REPO / "codemagic.yaml")
    bundle = re.search(r"SPX_BUNDLE_IDENTIFIER:\s*\"?([A-Za-z0-9.\-]+)\"?", text)
    require = re.search(r"SPX_REQUIRE_SIGNING_MATCH:\s*\"?([01])\"?", text)
    mode = re.search(r"SPX_SIGNING_MODE:\s*\"?(manual|automatic)\"?", text)
    return (
        bundle.group(1) if bundle else "",
        require.group(1) if require else "0",
        mode.group(1) if mode else "manual",
    )


def profile_values():
    """Every provisioning profile under signing/, annotated with whether it is archived."""
    base = IOS / "signing"
    paths = sorted(base.glob("*.mobileprovision")) + sorted(base.glob("*/*.mobileprovision"))
    profiles = []
    for path in paths:
        raw = path.read_bytes()
        start = raw.find(b"<?xml")
        end = raw.find(b"</plist>")
        if start < 0 or end < 0:
            NOTES.append("could not parse {}".format(path.name))
            continue
        try:
            plist = plistlib.loads(raw[start:end + len(b"</plist>")])
        except Exception as error:  # noqa: BLE001 - report and continue
            NOTES.append("could not parse {}: {}".format(path.name, error))
            continue
        entitlements = plist.get("Entitlements") or {}
        app_id = str(entitlements.get("application-identifier", ""))
        prefix, _, bundle = app_id.partition(".")
        teams = plist.get("TeamIdentifier") or [prefix]
        profiles.append({
            "file": path.name,
            "where": path.parent.relative_to(IOS).as_posix(),
            "app_id": app_id,
            "bundle": bundle,
            "team": str(teams[0]) if teams else prefix,
            "push": str(entitlements.get("aps-environment", "")),
            "expires": str(plist.get("ExpirationDate", "")),
            "archived": any(part.startswith("legacy-") for part in path.parts),
        })
    return profiles


def main():
    bundle, team, prefix = project_values()
    ci_bundle, require, mode = ci_values()
    profiles = profile_values()
    active = next((item for item in profiles if not item["archived"]), None)
    archived = [item for item in profiles if item["archived"]]

    print("bundle id / team consistency")
    print("  project.yml      bundle={!r} team={!r} prefix={!r}".format(bundle, team, prefix))
    print("  codemagic.yaml   bundle={!r} mode={!r} require_match={}".format(ci_bundle, mode, require))
    if active:
        print("  mobileprovision  {!r}".format(active["where"] + "/" + active["file"]))
        print("                   app_id={!r}".format(active["app_id"]))
        print("                   bundle={!r} team={!r} push={!r} expires={}".format(
            active["bundle"], active["team"], active["push"], active["expires"]))
    else:
        print("  mobileprovision  (no active profile under ios-native/signing/)")
    for item in archived:
        print("  archived         {!r} team={!r} bundle={!r} expires={}".format(
            item["where"] + "/" + item["file"], item["team"], item["bundle"], item["expires"]))

    if not bundle:
        PROBLEMS.append("project.yml has no PRODUCT_BUNDLE_IDENTIFIER")
    if not ci_bundle:
        PROBLEMS.append("codemagic.yaml has no SPX_BUNDLE_IDENTIFIER")
    if bundle and ci_bundle and bundle != ci_bundle:
        PROBLEMS.append(
            "codemagic.yaml SPX_BUNDLE_IDENTIFIER {!r} != project.yml {!r}".format(ci_bundle, bundle))
    if bundle and prefix and not bundle.startswith(prefix + "."):
        PROBLEMS.append(
            "bundle id {!r} does not start with bundleIdPrefix {!r}".format(bundle, prefix))

    if mode == "automatic":
        print("  automatic signing: profile checks skipped (managed by Xcode)")
    elif active:
        if bundle and active["bundle"] != bundle:
            PROBLEMS.append(
                "signing profile is issued for {!r} but the app builds as {!r}".format(
                    active["bundle"], bundle))
        if team and active["team"] != team:
            PROBLEMS.append(
                "signing profile belongs to team {!r} but project uses {!r}".format(
                    active["team"], team))
    else:
        print("  note: manual mode needs SPX_IOS_PROFILE_* / SPX_IOS_CERTIFICATE_* from CI secrets")

    if not PROBLEMS:
        print("OK: bundle id and team are consistent everywhere")
        for note in NOTES:
            print("  note:", note)
        return 0

    print("\nPROBLEMS:")
    for problem in PROBLEMS:
        print("  -", problem)

    print("""
Two ways to satisfy this (own Apple Developer account, team {team}):

A. Automatic signing (recommended, no files in the repo)
   1. Apple Developer portal -> Identifiers -> App IDs -> register an explicit App ID
      for bundle id {bundle}.
   2. App Store Connect -> Users and Access -> Integrations -> create an API key
      (App Manager role) and download AuthKey_<KEYID>.p8.
   3. Put SPX_ASC_KEY_BASE64 (or SPX_ASC_KEY_PATH), SPX_ASC_KEY_ID and
      SPX_ASC_ISSUER_ID into the CI secret group, then set SPX_SIGNING_MODE: "automatic".
   Xcode then creates and renews the certificate and profile on every build.

B. Manual signing (historical pipeline)
   1. Same App ID registration as above.
   2. Certificates, Identifiers & Profiles -> Profiles -> App Store profile for that App ID.
   3. Put the new .p12 and .mobileprovision under ios-native/signing/ (or into the
      SPX_IOS_CERTIFICATE_* / SPX_IOS_PROFILE_* CI secrets) and set
      SPX_IOS_P12_PASSWORD in the secret group.

Then set SPX_REQUIRE_SIGNING_MATCH: "1" in codemagic.yaml so this can never drift again.
""".format(team=team or "<team>", bundle=bundle or "<bundle id>"))

    if require == "1":
        print("FAILED: SPX_REQUIRE_SIGNING_MATCH=1")
        return 1
    print("WARNING only: set SPX_REQUIRE_SIGNING_MATCH=1 to make this fatal")
    return 0


if __name__ == "__main__":
    sys.exit(main())
