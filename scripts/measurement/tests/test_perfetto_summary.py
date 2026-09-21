import argparse
import pathlib
import tempfile
import unittest

import sys

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import perfetto_summary


class PerfettoTraceSummaryTest(unittest.TestCase):

    def test_partial_summary_binds_raw_trace_and_named_sections(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            trace = root / "trace.pftrace"
            trace.write_bytes(b"perfetto-test-bytes")

            args = argparse.Namespace(
                trace=trace,
                output=root / "summary.json",
                status="PARTIAL",
                limitation=["numeric extraction not populated in this smoke"],
                cpu_time_ms=None,
                max_rss_bytes=None,
                io_read_bytes=None,
                io_write_bytes=None,
                gc_time_ms=None,
                prepare_count=None,
                prepare_total_ms=None,
                seek_count=None,
                seek_total_ms=None,
                rebuffer_count=None,
                rebuffer_total_ms=None,
            )

            summary = perfetto_summary.build_summary(args)

            self.assertEqual(1, summary["schemaVersion"])
            self.assertEqual("PARTIAL", summary["status"])
            self.assertEqual(len(b"perfetto-test-bytes"), summary["rawTraceBytes"])
            self.assertEqual(
                "SpongeTube:M0:prepare",
                summary["customSections"]["prepare"]["traceName"],
            )
            self.assertEqual(
                "SpongeTube:M0:rebuffer",
                summary["customSections"]["rebuffer"]["traceName"],
            )

    def test_partial_summary_requires_limitation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            trace = root / "trace.pftrace"
            trace.write_bytes(b"x")

            args = argparse.Namespace(
                trace=trace,
                output=root / "summary.json",
                status="PARTIAL",
                limitation=[],
                cpu_time_ms=None,
                max_rss_bytes=None,
                io_read_bytes=None,
                io_write_bytes=None,
                gc_time_ms=None,
                prepare_count=None,
                prepare_total_ms=None,
                seek_count=None,
                seek_total_ms=None,
                rebuffer_count=None,
                rebuffer_total_ms=None,
            )

            with self.assertRaises(ValueError):
                perfetto_summary.build_summary(args)

    def test_empty_trace_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            trace = root / "trace.pftrace"
            trace.write_bytes(b"")

            args = argparse.Namespace(
                trace=trace,
                output=root / "summary.json",
                status="COMPLETE",
                limitation=[],
                cpu_time_ms=1.0,
                max_rss_bytes=1,
                io_read_bytes=1,
                io_write_bytes=1,
                gc_time_ms=0.0,
                prepare_count=1,
                prepare_total_ms=1.0,
                seek_count=0,
                seek_total_ms=0.0,
                rebuffer_count=0,
                rebuffer_total_ms=0.0,
            )

            with self.assertRaises(ValueError):
                perfetto_summary.build_summary(args)


if __name__ == "__main__":
    unittest.main()
