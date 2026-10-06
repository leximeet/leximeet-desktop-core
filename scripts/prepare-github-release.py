#!/usr/bin/env python3
"""复用三平台门禁通过的 Linux 产物，交付完整对应源码和逐文件摘要。"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = ("leximeet-core.jar", "leximeet-core-sources.jar", "bom.json", "bom.xml", "release-verification.json")


def sha256(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_candidate(directory):
    """拒绝未通过真实启动的候选及传输后与原检查摘要不符的产物。"""
    for name in PAYLOAD:
        file = directory / name
        if file.is_symlink() or not file.is_file():
            raise ValueError("缺少普通发行文件：" + name)
    evidence = json.loads((directory / "release-verification.json").read_text(encoding="utf-8"))
    if evidence.get("status") != "passed" or evidence.get("javaMajor") != 21 or evidence.get("temporaryProfileRemoved") is not True:
        raise ValueError("候选没有完成真实 JDK 21 启动和隔离回收")
    for name, field in (("leximeet-core.jar", "jarSha256"), ("leximeet-core-sources.jar", "sourcesSha256"), ("bom.json", "bomSha256")):
        if sha256(directory / name) != evidence.get(field):
            raise ValueError("候选摘要不一致：" + name)
    return evidence


def package_release(candidate, output, tag):
    version = ET.parse(ROOT / "pom.xml").findtext("{http://maven.apache.org/POM/4.0.0}version")
    if not re.fullmatch(r"\d+\.\d+\.\d+", tag or "") or version != tag:
        raise ValueError("正式标签必须与 pom.xml 版本一致，不加 v 前缀")
    git = lambda *args: subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()
    commit = git("rev-parse", "HEAD")
    if git("status", "--porcelain"):
        raise ValueError("对应源码必须来自干净、已提交的工作树")
    validate_candidate(candidate)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir()  # 不覆盖旧发行附件。
    for name in PAYLOAD:
        shutil.copyfile(candidate / name, output / name)
    shutil.copyfile(ROOT / "LICENSE", output / "LICENSE")
    archive = f"leximeet-desktop-core-{tag}-source.tar.gz"
    git("archive", "--format=tar.gz", "--prefix=leximeet-desktop-core/", f"--output={output / archive}", commit)
    files = [{"file": file.name, "bytes": file.stat().st_size, "sha256": sha256(file)} for file in sorted(output.iterdir())]
    metadata = {"format": "leximeet.core-release/1", "version": tag, "releaseTag": tag, "sourceCommit": commit, "sourceDirty": False, "candidatePlatform": "ubuntu-24.04", "verification": "three-platform-ci-and-real-jar", "files": files}
    (output / "SOURCE.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text("".join(f"{sha256(file)}  {file.name}\n" for file in sorted(output.iterdir())), encoding="utf-8")
    return metadata


if __name__ == "__main__":
    try:
        result = package_release(ROOT / "target", ROOT / ".runtime/github-release", os.environ.get("GITHUB_REF_NAME"))
        print("正式发行附件已核验：" + result["version"] + " " + result["sourceCommit"])
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
