#!/usr/bin/env python3
"""Exercise release creation and retry behavior without contacting GitHub."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
MOCK_GH = r'''
import json, os, pathlib, sys
args = sys.argv[1:]
call = {"args": args}
if args[:2] == ["release", "create"]:
    notes = pathlib.Path(args[args.index("--notes-file") + 1]).read_text()
    call["notes"] = notes
with open(os.environ["REVIEW_GH_CALLS"], "a") as log:
    log.write(json.dumps(call) + "\n")
if args[:2] == ["api", "repos/canardlapin/frame4s/releases"]:
    if "--paginate" not in args or "--jq" not in args:
        sys.exit(2)
    print(os.environ.get("REVIEW_GH_EXISTING", ""))
    sys.exit(int(os.environ.get("REVIEW_GH_READ_EXIT", "0")))
if args[:2] == ["release", "create"]:
    sys.exit(int(os.environ.get("REVIEW_GH_CREATE_EXIT", "0")))
sys.exit(2)
'''


class GitHubReleaseTests(unittest.TestCase):
    def run_release(self, tag, existing="", read_exit=0, create_exit=0):
        with tempfile.TemporaryDirectory(prefix="frame4s-release-test-") as directory:
            scratch = Path(directory)
            gh = scratch / "gh"
            gh.write_text("#!" + sys.executable + "\n" + textwrap.dedent(MOCK_GH))
            gh.chmod(0o755)
            calls_file = scratch / "calls.jsonl"
            environment = dict(
                os.environ,
                PATH=str(scratch) + os.pathsep + os.environ["PATH"],
                GITHUB_REF_NAME=tag,
                GITHUB_REPOSITORY="canardlapin/frame4s",
                RUNNER_TEMP=str(scratch),
                REVIEW_GH_CALLS=str(calls_file),
                REVIEW_GH_EXISTING=existing,
                REVIEW_GH_READ_EXIT=str(read_exit),
                REVIEW_GH_CREATE_EXIT=str(create_exit),
            )
            result = subprocess.run(
                ["bash", "scripts/github-release.sh"],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
            )
            calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
            creates = [call for call in calls if call["args"][:2] == ["release", "create"]]
            return result, creates

    def test_stable_creation_has_stable_notes_without_changing_source(self):
        notes = ROOT / "docs/release-notes/0.1.0.md"
        before = notes.read_bytes()
        result, creates = self.run_release("v0.1.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(creates), 1)
        self.assertNotIn("--prerelease", creates[0]["args"])
        self.assertIn("--verify-tag", creates[0]["args"])
        self.assertIn("Status: stable\n", creates[0]["notes"])
        self.assertNotIn("Status: release-candidate draft", creates[0]["notes"])
        self.assertEqual(notes.read_bytes(), before)

    def test_rc_creation_preserves_rc_notes(self):
        result, creates = self.run_release("v0.1.0-RC1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(creates), 1)
        self.assertIn("--prerelease", creates[0]["args"])
        self.assertIn("Status: release-candidate draft\n", creates[0]["notes"])

    def test_existing_release_retry_does_not_create_or_edit(self):
        for tag, status in [("v0.1.0", "false\tfalse"), ("v0.1.0-RC1", "false\ttrue")]:
            with self.subTest(tag=tag):
                result, creates = self.run_release(tag, existing=status)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(creates, [])

    def test_existing_release_with_wrong_status_is_rejected(self):
        for status in ["true\tfalse", "false\ttrue"]:
            with self.subTest(status=status):
                result, creates = self.run_release("v0.1.0", existing=status)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(creates, [])

    def test_api_failure_does_not_attempt_creation(self):
        result, creates = self.run_release("v0.1.0", read_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(creates, [])

    def test_creation_failure_is_propagated(self):
        result, creates = self.run_release("v0.1.0", create_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(creates), 1)


if __name__ == "__main__":
    unittest.main()
