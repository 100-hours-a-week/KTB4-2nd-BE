import hashlib
import json
import stat
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import generate_limit_test_accounts as generator


class GenerateLimitTestAccountsTest(unittest.TestCase):
    def test_generates_isolated_users_and_ten_sessions_without_plaintext_sql(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            generator.generate(output)
            data = json.loads((output / "accounts.json").read_text())
            accounts = data["accounts"]
            fixture = data["viewerFixtureSession"]
            seed = (output / "seed.sql").read_text()
            cleanup = (output / "cleanup.sql").read_text()

            self.assertEqual(len(accounts), 9)
            self.assertEqual([item["role"] for item in accounts],
                             ["creator"] * 8 + ["viewer"])
            self.assertEqual(len({item["userId"] for item in accounts}), 9)
            self.assertTrue(all(generator.ID_MIN <= item["userId"] < 2**53
                                for item in accounts))
            self.assertEqual(fixture["userId"], accounts[-1]["userId"])
            self.assertEqual(fixture["role"], "viewer_fixture")
            self.assertEqual(len({item["sid"] for item in [*accounts, fixture]}), 10)
            self.assertEqual(len({item["refreshToken"] for item in [*accounts, fixture]}), 10)
            self.assertEqual(seed.count("CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY)"), 10)
            self.assertIn("INSERT INTO user_stats", seed)
            self.assertIn("INSERT INTO consents", seed)
            self.assertNotIn("INSERT INTO oauth_accounts", seed)
            self.assertIn("registered_sessions = 10", seed)
            self.assertIn("safe_to_cleanup", cleanup)
            self.assertIn("AND sid NOT IN", cleanup)
            self.assertNotIn("DELETE FROM users", cleanup)
            self.assertNotIn("COMMIT;", seed + cleanup)

            for item in [*accounts, fixture]:
                self.assertEqual(uuid.UUID(item["sid"]).version, 4)
                self.assertIn(hashlib.sha256(item["refreshToken"].encode()).hexdigest(), seed)
                self.assertIn(item["sid"], seed + cleanup)
                self.assertNotIn(item["refreshToken"], seed + cleanup)
            for item in accounts:
                self.assertIn(item["email"], seed + cleanup)
                self.assertEqual(item["email"].split("@", 1)[1], "yeodam.invalid")

            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o700)
            for path in output.iterdir():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            output.mkdir()
            original = output / "accounts.json"
            original.write_text("keep")
            with self.assertRaises(FileExistsError):
                generator.generate(output)
            self.assertEqual(original.read_text(), "keep")

    def test_partial_output_is_removed_on_write_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            sibling = Path(temporary) / "keep"
            sibling.write_text("keep")
            real_write = generator.write_private
            calls = 0

            def fail_second_write(path, content):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("simulated write failure")
                real_write(path, content)

            with patch.object(generator, "write_private", side_effect=fail_second_write):
                with self.assertRaises(OSError):
                    generator.generate(output)
            self.assertFalse(output.exists())
            self.assertEqual(sibling.read_text(), "keep")

    def test_cli_prints_paths_without_tokens(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            result = subprocess.run(
                [sys.executable, str(Path(generator.__file__)), "--output-dir", str(output)],
                capture_output=True, text=True, check=True,
            )
            self.assertIn("9개", result.stdout)
            self.assertEqual(result.stderr, "")
            data = json.loads((output / "accounts.json").read_text())
            for item in [*data["accounts"], data["viewerFixtureSession"]]:
                self.assertNotIn(item["refreshToken"], result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
