"""Lightweight fixtures for CI result validation; no SDK, Gradle or emulator."""
import base64
import importlib.util
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location(
    "onboarding_instrumentation", Path(__file__).with_name("check-onboarding-instrumentation.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

SOURCE = '''
package example
class OnboardingFlowTest {
    @Test fun guest() { screenshot("welcome") }
    @Test fun google() {}
}
'''
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO6OtrcAAAAASUVORK5CYII=")


def runner_log(names=("guest", "google"), status=0, summary=2, final=-1):
    output = []
    for name in names:
        for code in (1, status):
            output += ["INSTRUMENTATION_STATUS: class=example.OnboardingFlowTest",
                       "INSTRUMENTATION_STATUS: test=" + name,
                       "INSTRUMENTATION_STATUS_CODE: " + str(code)]
    output += [f"OK ({summary} tests)", f"INSTRUMENTATION_CODE: {final}"]
    return "\n".join(output)


class InstrumentationContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        for filename in ("welcome.png", "preview-welcome.png"):
            (self.directory / filename).write_bytes(PNG)

    def errors(self, log):
        return module.assess(log, SOURCE, self.directory)[1]

    def test_accepts_all_successful_tests_and_complete_pngs(self):
        self.assertEqual([], self.errors(runner_log()))

    def test_rejects_zero_tests_even_if_transport_exit_is_zero(self):
        self.assertTrue(self.errors(runner_log(names=(), summary=0)))

    def test_rejects_missing_test_despite_success_summary(self):
        self.assertTrue(self.errors(runner_log(names=("guest",))))

    def test_rejects_duplicate_or_wrong_tests(self):
        self.assertTrue(self.errors(runner_log(names=("guest", "guest"))))
        self.assertTrue(self.errors(runner_log(names=("guest", "unrelated"))))

    def test_rejects_failed_crashed_ignored_or_assumption_failed_test(self):
        for status in (-1, -2, -3, -4):
            with self.subTest(status=status):
                self.assertTrue(self.errors(runner_log(status=status)))

    def test_rejects_crashed_instrumentation(self):
        self.assertTrue(self.errors(runner_log(final=0)))
        self.assertTrue(self.errors(runner_log() + "\nINSTRUMENTATION_FAILED: process crashed"))

    def test_requires_success_summary_and_final_code(self):
        self.assertTrue(self.errors(runner_log().replace("OK (2 tests)", "")))
        self.assertTrue(self.errors(runner_log().replace("INSTRUMENTATION_CODE: -1", "")))

    def test_rejects_missing_or_truncated_screenshot(self):
        (self.directory / "welcome.png").unlink()
        self.assertTrue(self.errors(runner_log()))
        (self.directory / "welcome.png").write_bytes(PNG[:-8])
        self.assertTrue(self.errors(runner_log()))

    def test_rejects_empty_preview(self):
        (self.directory / "preview-welcome.png").write_bytes(b"")
        self.assertTrue(self.errors(runner_log()))

    def test_no_test_source_is_an_error(self):
        with self.assertRaises(ValueError):
            module.expected_tests("package example\nclass Empty {}")

    def test_report_keeps_test_failures_and_full_output(self):
        output = runner_log(status=-2)
        completed, errors = module.assess(output, SOURCE, self.directory)
        path = self.directory / "results.xml"
        module.write_report(path, completed, errors, output)
        report = ET.parse(path).getroot()
        self.assertGreater(int(report.attrib["failures"]), 0)
        self.assertEqual(output, report.find("system-out").text)


if __name__ == "__main__":
    unittest.main()
