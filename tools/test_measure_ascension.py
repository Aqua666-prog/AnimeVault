import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("measure", Path(__file__).with_name("measure-ascension.py"))
measure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measure)


class MeasureAscensionTest(unittest.TestCase):
    def test_startup_uses_reported_mode_and_distinct_timings(self):
        result = measure.startup_metrics("Status: ok\nLaunchState: HOT\nTotalTime: 42\nWaitTime: 59\n")
        self.assertEqual(result, {"state": "HOT", "status": "ok", "total_ms": 42, "wait_ms": 59})
        self.assertIsNone(measure.startup_metrics("Status: ok")["state"])

    def test_frame_metrics_skip_flagged_entries_and_use_real_deadlines(self):
        raw = """---PROFILEDATA---
Flags,IntendedVsync,FrameDeadline,FrameCompleted,
0,10000000,20000000,17000000,
0,20000000,30000000,38000000,
1,20000000,30000000,98000000,
0,40000000,0,50000000,
---PROFILEDATA---
"""
        result = measure.frame_metrics(raw)
        self.assertEqual(result["sample_count"], 3)
        self.assertEqual(result["p50_ms"], 10)
        self.assertEqual(result["p99_ms"], 18)
        self.assertEqual(result["frames_with_deadline"], 2)
        self.assertEqual(result["missed_fraction"], 0.5)

    def test_empty_data_does_not_invent_a_frame_time(self):
        self.assertIsNone(measure.frame_metrics("")["p95_ms"])
