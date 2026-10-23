"""Offline checks for signing selection and exported IPA validation."""

import datetime as dt
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

from build_signed_ipa import BuildFailure, profile_settings, verify_ipa


class SigningConfigurationTests(unittest.TestCase):
    def profile(self, **changes):
        value = {
            "UUID": "test-profile", "Name": "Test profile", "TeamIdentifier": ["TEAM123456"],
            "ExpirationDate": dt.datetime(2099, 1, 1), "DeveloperCertificates": [b"fixture-certificate"],
            "ProvisionedDevices": ["fixture-device"],
            "Entitlements": {"application-identifier": "TEAM123456.com.example.spx", "get-task-allow": False},
        }
        value.update(changes)
        return value

    def test_ad_hoc_identifier_and_device_count(self):
        result = profile_settings(self.profile())
        self.assertEqual(result["bundleID"], "com.example.spx")
        self.assertEqual(result["deviceCount"], 1)
        self.assertEqual(result["method"], "release-testing")
        self.assertFalse(result["debugAllowed"])

    def test_expired_and_wrong_bundle_are_rejected(self):
        with self.assertRaises(BuildFailure):
            profile_settings(self.profile(ExpirationDate=dt.datetime(2000, 1, 1)))
        with self.assertRaises(BuildFailure):
            profile_settings(self.profile(), "com.example.other")

    def test_distribution_methods_follow_profile(self):
        development = self.profile()
        development["Entitlements"]["get-task-allow"] = True
        self.assertEqual(profile_settings(development)["method"], "debugging")
        self.assertEqual(profile_settings(self.profile(ProvisionsAllDevices=True))["method"], "enterprise")
        self.assertEqual(profile_settings(self.profile(ProvisionedDevices=[]))["method"], "app-store-connect")

    def test_wildcard_requires_matching_concrete_identifier(self):
        profile = self.profile(Entitlements={"application-identifier": "TEAM123456.com.example.*"})
        with self.assertRaises(BuildFailure):
            profile_settings(profile)
        with self.assertRaises(BuildFailure):
            profile_settings(profile, "com.other.app")
        self.assertEqual(profile_settings(profile, "com.example.spx")["bundleID"], "com.example.spx")

    def test_exported_ipa_checks_identifier_build_and_profile(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            with zipfile.ZipFile(ipa, "w") as package:
                package.writestr("Payload/Test.app/Info.plist", plistlib.dumps({
                    "CFBundleIdentifier": settings["bundleID"], "CFBundleVersion": "7", "SPXNetworkTrust": "system-public-ip-v1"}))
                package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
            self.assertEqual(verify_ipa(ipa, settings, "7")["CFBundleVersion"], "7")
            with self.assertRaises(BuildFailure):
                verify_ipa(ipa, settings, "8")
            with self.assertRaises(BuildFailure):
                verify_ipa(ipa, {**settings, "bundleID": "com.example.other"}, "7")

    def test_exported_ipa_without_profile_is_rejected(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            with zipfile.ZipFile(ipa, "w") as package:
                package.writestr("Payload/Test.app/Info.plist", plistlib.dumps({
                    "CFBundleIdentifier": settings["bundleID"], "CFBundleVersion": "7", "SPXNetworkTrust": "system-public-ip-v1"}))
            with self.assertRaises(BuildFailure):
                verify_ipa(ipa, settings, "7")


    def test_exported_ipa_rejects_old_network_configuration(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "old.ipa"
            for extra in [{}, {"SPXNetworkTrust": "system-public-ip-v1", "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True}}]:
                with zipfile.ZipFile(ipa, "w") as package:
                    package.writestr("Payload/Test.app/Info.plist", plistlib.dumps({
                        "CFBundleIdentifier": settings["bundleID"], "CFBundleVersion": "7", **extra}))
                    package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
                with self.assertRaises(BuildFailure):
                    verify_ipa(ipa, settings, "7")

if __name__ == "__main__":
    unittest.main()
