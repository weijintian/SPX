"""Offline checks for signing selection and exported IPA validation."""

import datetime as dt
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

from build_signed_ipa import BuildFailure, prepare_signing_info_plist, profile_settings, verify_ipa


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

    def app_info(self, settings, **changes):
        value = {
            "CFBundleIdentifier": settings["bundleID"], "CFBundleVersion": "7",
            "SPXNetworkTrust": "system-public-ip-v1", "UIDeviceFamily": [1],
            "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"],
        }
        value.update(changes)
        return value

    def test_ad_hoc_identifier_and_device_count(self):
        result = profile_settings(self.profile())
        self.assertEqual(result["bundleID"], "com.example.spx")
        self.assertEqual(result["deviceCount"], 1)
        self.assertEqual(result["method"], "release-testing")
        self.assertFalse(result["debugAllowed"])

    def test_push_entitlement_is_optional(self):
        self.assertEqual(profile_settings(self.profile())["pushEnvironment"], "")
        value = self.profile()
        value["Entitlements"]["aps-environment"] = "production"
        self.assertEqual(profile_settings(value)["pushEnvironment"], "production")

    def test_signing_plist_materializes_push_environment_and_preserves_source(self):
        source = Path(__file__).resolve().parents[1] / "SpxChatIOS/Info.plist"
        original = source.read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "Info.plist"
            for environment in ["development", "production", ""]:
                prepare_signing_info_plist(source, target, {"pushEnvironment": environment})
                info = plistlib.loads(target.read_bytes())
                self.assertEqual(info.get("SPXPushEnvironment", ""), environment)
                self.assertEqual(info["SPXNetworkTrust"], "system-public-ip-v1")
                self.assertIn("NSCameraUsageDescription", info)
                self.assertNotIn("NSAppTransportSecurity", info)
            with self.assertRaises(BuildFailure):
                prepare_signing_info_plist(source, target, {"pushEnvironment": "invalid"})
        self.assertEqual(source.read_bytes(), original)

    def test_generated_signing_plist_passes_export_push_validation(self):
        source = Path(__file__).resolve().parents[1] / "SpxChatIOS/Info.plist"
        profile = self.profile()
        profile["Entitlements"]["aps-environment"] = "production"
        settings = profile_settings(profile)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "Info.plist"
            prepare_signing_info_plist(source, target, settings)
            info = plistlib.loads(target.read_bytes())
            info.update(CFBundleIdentifier=settings["bundleID"], CFBundleVersion="7", UIDeviceFamily=[1])
            ipa = Path(directory) / "test.ipa"
            with zipfile.ZipFile(ipa, "w") as package:
                package.writestr("Payload/Test.app/Info.plist", plistlib.dumps(info))
                package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
            self.assertEqual(verify_ipa(ipa, settings, "7")["SPXPushEnvironment"], "production")

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
                package.writestr("Payload/Test.app/Info.plist", plistlib.dumps(self.app_info(settings)))
                package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
            self.assertEqual(verify_ipa(ipa, settings, "7")["CFBundleVersion"], "7")
            with self.assertRaises(BuildFailure):
                verify_ipa(ipa, settings, "8")
            with self.assertRaises(BuildFailure):
                verify_ipa(ipa, {**settings, "bundleID": "com.example.other"}, "7")

    def test_exported_ipa_requires_push_environment_when_profile_supports_it(self):
        profile = self.profile()
        profile["Entitlements"]["aps-environment"] = "production"
        settings = profile_settings(profile)
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            for environment in [None, "development", "production"]:
                info = self.app_info(settings)
                if environment:
                    info["SPXPushEnvironment"] = environment
                with zipfile.ZipFile(ipa, "w") as package:
                    package.writestr("Payload/Test.app/Info.plist", plistlib.dumps(info))
                    package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
                if environment == "production":
                    self.assertEqual(verify_ipa(ipa, settings, "7")["SPXPushEnvironment"], environment)
                else:
                    with self.assertRaises(BuildFailure):
                        verify_ipa(ipa, settings, "7")

    def test_exported_ipa_without_profile_is_rejected(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            with zipfile.ZipFile(ipa, "w") as package:
                package.writestr("Payload/Test.app/Info.plist", plistlib.dumps(self.app_info(settings)))
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

    def test_exported_ipa_rejects_wrong_or_missing_device_family(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            for family in ([1, 2], [2], [], [True], "1", None):
                with self.subTest(device_family=family):
                    info = self.app_info(settings, UIDeviceFamily=family)
                    if family is None:
                        info.pop("UIDeviceFamily")
                    with zipfile.ZipFile(ipa, "w") as package:
                        package.writestr("Payload/Test.app/Info.plist", plistlib.dumps(info))
                        package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
                    with self.assertRaisesRegex(BuildFailure, "UIDeviceFamily"):
                        verify_ipa(ipa, settings, "7")

    def test_exported_ipa_preserves_portrait_only_orientation(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            cases = ([], ["UIInterfaceOrientationLandscapeLeft"],
                     ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeRight"],
                     "UIInterfaceOrientationPortrait", None)
            for orientations in cases:
                with self.subTest(orientations=orientations):
                    info = self.app_info(settings, UISupportedInterfaceOrientations=orientations)
                    if orientations is None:
                        info.pop("UISupportedInterfaceOrientations")
                    with zipfile.ZipFile(ipa, "w") as package:
                        package.writestr("Payload/Test.app/Info.plist", plistlib.dumps(info))
                        package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
                    with self.assertRaisesRegex(BuildFailure, "UISupportedInterfaceOrientations"):
                        verify_ipa(ipa, settings, "7")

    def test_exported_ipa_accepts_binary_plist_for_portrait_iphone(self):
        settings = profile_settings(self.profile())
        with tempfile.TemporaryDirectory() as directory:
            ipa = Path(directory) / "test.ipa"
            with zipfile.ZipFile(ipa, "w") as package:
                package.writestr("Payload/Test.app/Info.plist",
                                 plistlib.dumps(self.app_info(settings), fmt=plistlib.FMT_BINARY))
                package.writestr("Payload/Test.app/embedded.mobileprovision", b"fixture")
            info = verify_ipa(ipa, settings, "7")
            self.assertEqual(info["UIDeviceFamily"], [1])
            self.assertEqual(info["UISupportedInterfaceOrientations"], ["UIInterfaceOrientationPortrait"])

if __name__ == "__main__":
    unittest.main()
