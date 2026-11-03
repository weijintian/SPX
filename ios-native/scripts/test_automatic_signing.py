"""Offline checks for signing-mode selection and the automatic-signing settings."""

import os
import unittest
from unittest import mock

from build_signed_ipa import BuildFailure, automatic_settings, developer_team, signing_mode


class SigningModeTests(unittest.TestCase):
    def test_explicit_mode_wins(self):
        with mock.patch.dict(os.environ, {"SPX_SIGNING_MODE": "automatic"}, clear=True):
            self.assertEqual(signing_mode(), "automatic")
        with mock.patch.dict(os.environ, {"SPX_SIGNING_MODE": "manual", "SPX_ASC_KEY_ID": "KEY"}, clear=True):
            self.assertEqual(signing_mode(), "manual")

    def test_an_asc_key_alone_selects_automatic(self):
        with mock.patch.dict(os.environ, {"SPX_ASC_KEY_ID": "KEY"}, clear=True):
            self.assertEqual(signing_mode(), "automatic")

    def test_manual_material_keeps_the_manual_pipeline(self):
        environment = {"SPX_ASC_KEY_ID": "KEY", "SPX_IOS_CERTIFICATE_PATH": "/tmp/distribution.p12"}
        with mock.patch.dict(os.environ, environment, clear=True):
            self.assertEqual(signing_mode(), "manual")

    def test_default_is_manual(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(signing_mode(), "manual")


class DeveloperTeamTests(unittest.TestCase):
    def test_environment_wins(self):
        with mock.patch.dict(os.environ, {"SPX_DEVELOPMENT_TEAM": "FROMENV"}, clear=True):
            self.assertEqual(developer_team(), "FROMENV")

    def test_falls_back_to_project_yml(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(developer_team(), "VX2SVTNSZ3")


class AutomaticSettingsTests(unittest.TestCase):
    def setUp(self):
        self.environment = {
            "SPX_BUNDLE_IDENTIFIER": "com.spx.chat.ios",
            "SPX_DEVELOPMENT_TEAM": "VX2SVTNSZ3",
        }

    def test_store_export_without_push(self):
        with mock.patch.dict(os.environ, self.environment, clear=True):
            settings = automatic_settings()
        self.assertEqual(settings["bundleID"], "com.spx.chat.ios")
        self.assertEqual(settings["teamID"], "VX2SVTNSZ3")
        self.assertEqual(settings["prefix"], "VX2SVTNSZ3")
        self.assertEqual(settings["method"], "app-store-connect")
        self.assertEqual(settings["pushEnvironment"], "")
        self.assertFalse(settings["debugAllowed"])
        self.assertEqual(settings["uuid"], "")

    def test_push_environment_is_carried_through(self):
        environment = dict(self.environment, SPX_PUSH_ENVIRONMENT="production")
        with mock.patch.dict(os.environ, environment, clear=True):
            self.assertEqual(automatic_settings()["pushEnvironment"], "production")

    def test_invalid_push_environment_is_rejected(self):
        environment = dict(self.environment, SPX_PUSH_ENVIRONMENT="sandbox")
        with mock.patch.dict(os.environ, environment, clear=True):
            with self.assertRaises(BuildFailure):
                automatic_settings()

    def test_wildcard_or_empty_bundle_is_rejected(self):
        for bundle in ("", "com.spx.chat.*", "spx chat", "../../etc"):
            environment = dict(self.environment, SPX_BUNDLE_IDENTIFIER=bundle)
            with mock.patch.dict(os.environ, environment, clear=True):
                with self.assertRaises(BuildFailure):
                    automatic_settings()


if __name__ == "__main__":
    unittest.main()
