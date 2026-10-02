import base64
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

import generate_vu_refresh_tokens as generator


class GenerateVuRefreshTokensTest(unittest.TestCase):
    def test_generates_seven_tokens_and_matching_sql_with_private_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            generator.generate(list(range(2, 9)), output)
            data = json.loads((output / "tokens.json").read_text())
            tokens = data["tokens"]
            seed = (output / "seed.sql").read_text()
            cleanup = (output / "cleanup.sql").read_text()
            self.assertEqual(data["runId"], "run")
            self.assertEqual([item["vu"] for item in tokens], list(range(1, 8)))
            self.assertEqual([item["userId"] for item in tokens], list(range(2, 9)))
            self.assertEqual(len({item["refreshToken"] for item in tokens}), 7)
            self.assertEqual(len({item["sid"] for item in tokens}), 7)
            for item in tokens:
                token = item["refreshToken"]
                self.assertEqual(len(base64.urlsafe_b64decode(token + "=")), 32)
                self.assertNotIn("=", token)
                self.assertEqual(uuid.UUID(item["sid"]).version, 4)
                self.assertIn(hashlib.sha256(token.encode()).hexdigest(), seed)
                self.assertIn(item["sid"], seed)
                self.assertIn(item["sid"], cleanup)
                self.assertNotIn(token, seed + cleanup)
            self.assertIn("START TRANSACTION;", seed)
            self.assertIn("FOR UPDATE;", seed)
            self.assertIn("active_count = 7", seed)
            self.assertIn("INTERVAL 7 DAY", seed)
            self.assertNotIn("COMMIT;", seed + cleanup)
            self.assertNotIn("DELETE FROM users", cleanup)
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o700)
            for path in output.iterdir():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_rejects_invalid_ids_before_creating_files(self):
        invalid_inputs = [[], list(range(2, 8)), list(range(2, 10)),
                          [2] * 7, [1, 3, 4, 5, 6, 7, 8],
                          [0, 3, 4, 5, 6, 7, 8], [-2, 3, 4, 5, 6, 7, 8],
                          [2**63, 3, 4, 5, 6, 7, 8],
                          [True, 3, 4, 5, 6, 7, 8], ["2", 3, 4, 5, 6, 7, 8]]
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            for ids in invalid_inputs:
                with self.subTest(ids=ids), self.assertRaises(ValueError):
                    generator.generate(ids, output)
                self.assertFalse(output.exists())

    def test_accepts_signed_bigint_maximum(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            generator.generate([2**63 - 1, 3, 4, 5, 6, 7, 8], output)
            self.assertIn(str(2**63 - 1), (output / "seed.sql").read_text())

    def test_does_not_overwrite_existing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            output.mkdir()
            original = output / "tokens.json"
            original.write_text("keep")
            with self.assertRaises(FileExistsError):
                generator.generate(list(range(2, 9)), output)
            self.assertEqual(original.read_text(), "keep")

    def test_removes_only_partial_output_on_write_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            sibling = Path(temporary) / "keep"
            sibling.write_text("keep")
            real_write = generator.write_private
            count = 0

            def fail_second_write(path, content):
                nonlocal count
                count += 1
                if count == 2:
                    raise OSError("simulated write failure")
                real_write(path, content)

            with patch.object(generator, "write_private", side_effect=fail_second_write):
                with self.assertRaises(OSError):
                    generator.generate(list(range(2, 9)), output)
            self.assertFalse(output.exists())
            self.assertEqual(sibling.read_text(), "keep")

    def test_cli_prints_paths_but_never_tokens(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            result = subprocess.run(
                [sys.executable, str(Path(generator.__file__)), "--user-ids",
                 *map(str, range(2, 9)), "--output-dir", str(output)],
                capture_output=True, text=True, check=True,
            )
            self.assertIn("7", result.stdout)
            self.assertIn(str(output), result.stdout)
            self.assertEqual(result.stderr, "")
            for item in json.loads((output / "tokens.json").read_text())["tokens"]:
                self.assertNotIn(item["refreshToken"], result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
