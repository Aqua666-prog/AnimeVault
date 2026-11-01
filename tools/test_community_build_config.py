import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location("community_config",Path(__file__).with_name("verify-community-config.py"))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class CommunityBuildConfigTest(unittest.TestCase):
    def test_missing_configuration_keeps_optional_feature_disabled(self):
        self.assertEqual((False,False),module.inspect_config({}))
    def test_valid_public_key_activates_without_an_extra_hidden_flag(self):
        self.assertEqual((True,True),module.inspect_config({"SUPABASE_PUBLISHABLE_KEY":"sb_publishable_test"}))
    def test_explicit_disable_is_respected(self):
        self.assertEqual((False,True),module.inspect_config({"SUPABASE_PUBLISHABLE_KEY":"sb_publishable_test","COMMUNITY_ENABLED":"false"}))
    def test_secret_keys_and_wrong_projects_are_rejected(self):
        for key in ["service_role","sb_secret_test","eyJnotapublishablekey"]:
            self.assertEqual((True,False),module.inspect_config({"COMMUNITY_ENABLED":"true","SUPABASE_PUBLISHABLE_KEY":key}))
        self.assertEqual((True,False),module.inspect_config({"SUPABASE_URL":"https://other.invalid","SUPABASE_PUBLISHABLE_KEY":"sb_publishable_test"}))
    def test_blank_ci_url_uses_expected_project(self):
        self.assertEqual((True,True),module.inspect_config({"SUPABASE_URL":"","SUPABASE_PUBLISHABLE_KEY":"sb_publishable_test"}))

if __name__=="__main__":unittest.main()
