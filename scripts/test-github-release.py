"""发行附件的真实文件摘要与启动门禁负向回归，不启动应用或修改日常资料。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("prepare-github-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def test_reject_unverified_or_modified_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="leximeet-core-release-") as owned:
            directory = Path(owned)
            for name in release.PAYLOAD:
                (directory / name).write_bytes(b"verified")
            evidence = {"status": "passed", "javaMajor": 21, "temporaryProfileRemoved": True, "jarSha256": release.sha256(directory / "leximeet-core.jar"), "sourcesSha256": release.sha256(directory / "leximeet-core-sources.jar"), "bomSha256": release.sha256(directory / "bom.json")}
            record = directory / "release-verification.json"
            record.write_text(json.dumps(evidence), encoding="utf-8")
            release.validate_candidate(directory)
            evidence["status"] = "failed"
            record.write_text(json.dumps(evidence), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "真实 JDK"):
                release.validate_candidate(directory)
            evidence["status"] = "passed"
            record.write_text(json.dumps(evidence), encoding="utf-8")
            (directory / "leximeet-core.jar").write_bytes(b"modified")
            with self.assertRaisesRegex(ValueError, "摘要"):
                release.validate_candidate(directory)


if __name__ == "__main__":
    unittest.main()
