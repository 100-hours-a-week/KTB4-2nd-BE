import hashlib
import json
import tarfile
import unittest
from unittest.mock import patch
from pathlib import Path

from fixture import generate
import run

ROOT = Path(__file__).resolve().parent


class HarnessTest(unittest.TestCase):
    def test_fixture_matches_original_sql(self):
        self.assertEqual(hashlib.sha256(generate().encode()).hexdigest(),
                         "f46444b7aa96695537592c327addcc95ad90de64139b1e25ed2fbef9f3869801")

    def test_fixture_refuses_existing_business_data(self):
        with patch.object(run, "sql", return_value="1\n") as sql, patch.object(run, "auth") as auth:
            with self.assertRaises(SystemExit):
                run.seed_fixture()
            self.assertEqual(sql.call_count, 1)
            auth.assert_not_called()

    def test_fixture_accepts_migration_system_user(self):
        with patch.object(run, "sql", side_effect=["0\n", ""]) as sql, patch.object(run, "auth"), patch("builtins.print"):
            run.seed_fixture()
            self.assertEqual(sql.call_args_list[1].args, (generate(),))

    def test_archive_preserves_all_files_and_valid_runs(self):
        checksums = json.loads((ROOT / "baseline/checksums.json").read_text())
        with tarfile.open(ROOT / "baseline/measurements.tar.gz") as archive:
            files = {m.name: archive.extractfile(m).read() for m in archive if m.isfile()}
        self.assertEqual(set(files), set(checksums))
        self.assertEqual(len(files), 73)
        for path, data in files.items():
            self.assertEqual(hashlib.sha256(data).hexdigest(), checksums[path], path)
        results = [json.loads(v) for k, v in files.items() if k.endswith("/result.json")]
        self.assertEqual(len(results), 9)
        self.assertEqual(sum(r["poll_requests"] for r in results), 249304)
        for result in results:
            self.assertEqual(result["exit_code"], 0)
            self.assertEqual(result["poll_errors"]["rate"], 0)
            self.assertEqual(result["map_probe_errors"]["rate"], 0)
            self.assertEqual(result["progress_field_absent"]["rate"], 1)
            self.assertTrue(all(v["received_users"] == result["vus"]
                                for v in result["status_delivery"].values()))

    def test_compose_uses_isolated_local_services(self):
        config = json.loads((ROOT / "compose.json").read_text())
        self.assertEqual(config["volumes"]["mysql-data"]["name"], "yeodam-pollbase-share-mysql-data")
        services = config["services"]
        for service in services.values():
            for port in service.get("ports", []):
                self.assertTrue(port.startswith("127.0.0.1:"))
        for name in ("be1", "be2"):
            backend = services[name]
            self.assertEqual(backend["cpus"], 1)
            self.assertEqual(backend["mem_limit"], "2g")
            self.assertEqual(backend["environment"]["DOTENV_PATH"], "/missing.env")
            self.assertEqual(backend["build"]["context"], "./.runtime/backend-source")

    def test_python_scripts_compile(self):
        for script in ROOT.glob("*.py"):
            compile(script.read_text(), str(script), "exec")

    def test_polling_and_ai_stub_match_measured_sources(self):
        environment = json.loads((ROOT / "baseline/environment.json").read_text())
        for name in ("polling.js", "ai_stub.py"):
            self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(),
                             environment["final_harness_sha256"][name])


if __name__ == "__main__":
    unittest.main()
