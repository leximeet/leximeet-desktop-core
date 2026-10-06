#!/usr/bin/env python3
"""检查本轮本地构建产物，并在独占临时资料中启动真实可执行 Jar；不发布文件。"""

from contextlib import closing
import hashlib
import io
import json
import os
from pathlib import Path
import queue
import secrets
import sqlite3
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import zipfile

# Windows runner 的重定向输出可能默认使用 cp1252，显式保留中文验证结果。
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/leximeet-core.jar"
SOURCES = ROOT / "target/leximeet-core-sources.jar"
BOM = ROOT / "target/bom.json"


def check_artifacts():
    for path in (JAR, SOURCES, BOM, ROOT / "target/bom.xml"):
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError(f"构建缺少产物：{path.relative_to(ROOT)}")
    with zipfile.ZipFile(JAR) as jar:
        entries = set(jar.namelist())
        required = {
            "BOOT-INF/classes/app/leximeet/core/Main.class",
            "BOOT-INF/classes/lmcp/contract-manifest.json",
            "META-INF/leximeet/LICENSE",
            "META-INF/leximeet/THIRD_PARTY_LICENSES/java-fsrs-MIT.txt",
            "META-INF/leximeet/docs/依赖与许可证.md",
        }
        retired = {"StudyPlanService", "ReviewService", "TodayQueue", "Proficiency", "LearningMetrics", "CsvVocabulary"}
        if any("BOOT-INF/classes/app/leximeet/core/" + name + ".class" in entries for name in retired):
            raise RuntimeError("生产 Jar 仍含已退休的实验领域类")
        if not required <= entries:
            raise RuntimeError("Jar 缺少启动类、冻结协议或许可证")
        for source in (ROOT / "LICENSE", ROOT / "THIRD_PARTY_LICENSES/java-fsrs-MIT.txt"):
            if jar.read("META-INF/leximeet/" + source.relative_to(ROOT).as_posix()) != source.read_bytes():
                raise RuntimeError("随包项目或第三方许可与原声明不一致")
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        if "Start-Class: app.leximeet.core.Main" not in manifest:
            raise RuntimeError("Jar 不是约定的 Spring Boot 可执行产物")
        libs = [name for name in entries if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")]
        packaged = set()
        for name in libs:
            with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                # fsrs 发布 Jar 未携带许可，项目在 META-INF/leximeet 中补齐原 MIT 全文。
                if "fsrs-1.0.0.jar" not in name and not any(
                    "license" in entry.rsplit("/", 1)[-1].lower() for entry in nested.namelist()
                ):
                    raise RuntimeError(f"嵌套依赖缺少许可证：{name}")
            packaged.add(name.rsplit("/", 1)[-1])
        if not any("fsrs-1.0.0.jar" in name for name in libs):
            raise RuntimeError("Jar 未保留固定 FSRS 依赖")
        if any("junit" in name for name in libs):
            raise RuntimeError("测试依赖进入生产 Jar")
        # 冻结资源按原字节打包，不能由排版工具或 resource filtering 修改。
        for source in (ROOT / "src/main/resources/lmcp").rglob("*"):
            if source.is_file():
                name = "BOOT-INF/classes/" + source.relative_to(ROOT / "src/main/resources").as_posix()
                if jar.read(name) != source.read_bytes():
                    raise RuntimeError(f"冻结资源打包字节不一致：{name}")
    with zipfile.ZipFile(SOURCES) as source_jar:
        if "app/leximeet/core/Main.java" not in source_jar.namelist():
            raise RuntimeError("源码 Jar 缺少 Main.java")
    bom = json.loads(BOM.read_text(encoding="utf-8"))
    if bom.get("bomFormat") != "CycloneDX" or bom.get("specVersion") != "1.6":
        raise RuntimeError("SBOM 格式不符合约定")
    # 自有程序必须明确为固定第3版，不能被SBOM解析器映为过时/模糊的AGPL-3.0。
    own_licenses = bom.get("metadata", {}).get("component", {}).get("licenses", [])
    if not any(item.get("license", {}).get("id") == "AGPL-3.0-only" for item in own_licenses):
        raise RuntimeError("项目 SBOM 没有精确声明 AGPL-3.0-only")
    components = bom.get("components", [])
    declared = {f"{component['name']}-{component['version']}.jar" for component in components}
    if declared != packaged:
        raise RuntimeError("实际嵌套依赖与运行时 SBOM 不一致")
    if any(not component.get("licenses") for component in components):
        raise RuntimeError("运行时 SBOM 存在未声明许可的依赖")
    if not any(component.get("name") == "fsrs" and component.get("version") == "1.0.0" for component in components):
        raise RuntimeError("SBOM 缺少固定 FSRS")
    if any(component.get("group", "").startswith("org.junit") for component in components):
        raise RuntimeError("测试依赖进入运行时 SBOM")
    return len(components)


def smoke(java):
    token = secrets.token_urlsafe(36)
    with tempfile.TemporaryDirectory(prefix="leximeet-core-release-") as owned:
        directory = Path(owned)
        with (directory / "stderr.log").open("w", encoding="utf-8") as stderr:
            process = subprocess.Popen(
                [str(java), "-jar", str(JAR), "--data-dir", str(directory / "profile"),
                 "--token", token, "--profile", "test"],
                stdout=subprocess.PIPE, stderr=stderr, text=True, encoding="utf-8",
            )
            try:
                lines = queue.Queue()
                thread = threading.Thread(target=lambda: lines.put(process.stdout.readline()), daemon=True)
                thread.start()
                ready = json.loads(lines.get(timeout=15))
                if ready.get("profile") != "test" or not str(ready.get("startup", {}).get("javaVersion", "")).startswith("21."):
                    raise RuntimeError("真实 Jar 握手没有使用 JDK 21/test 资料")
                origin = f"http://127.0.0.1:{ready['port']}"
                # 忽略机器代理，只访问本次新进程的回环动态端口。
                http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                try:
                    http.open(origin + "/health", timeout=5)
                    raise RuntimeError("未授权请求意外成功")
                except urllib.error.HTTPError as error:
                    if error.code != 401:
                        raise RuntimeError("未授权请求未返回 401") from error
                request = urllib.request.Request(origin + "/api/desktop/state", headers={"Authorization": "Bearer " + token})
                with http.open(request, timeout=5) as response:
                    state = json.load(response)
                    if not isinstance(state, dict):
                        raise RuntimeError("真实 Jar 状态读取未返回对象")
                # 产物必须只暴露正式命令；退休路由不能解析/回显私人正文。
                headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
                command = {"action": "collect", "word": "release-fixture", "note": "隔离产物验证"}
                request = urllib.request.Request(origin + "/api/desktop/command", data=json.dumps(command).encode(), headers=headers, method="POST")
                with http.open(request, timeout=5) as response:
                    if not isinstance(json.load(response), dict):
                        raise RuntimeError("真实 Jar 正式收藏未返回状态")
                for route in ("/api/words", "/api/words/example/trash", "/api/books", "/api/books/example/delete", "/api/tags", "/api/tags/example/delete"):
                    request = urllib.request.Request(origin + route, data=b"{private-malformed", headers=headers, method="POST")
                    try:
                        http.open(request, timeout=5)
                        raise RuntimeError("真实 Jar 仍开放已退休的资料写入口")
                    except urllib.error.HTTPError as error:
                        if error.code != 404:
                            raise RuntimeError("真实 Jar 退休入口没有返回 404") from error
                if not (directory / "profile/leximeet.sqlite").is_file():
                    raise RuntimeError("真实 Jar 未初始化本轮隔离 SQLite")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                if process.stdout:
                    process.stdout.close()
        if token in (directory / "stderr.log").read_text(encoding="utf-8"):
            raise RuntimeError("诊断日志泄露临时令牌")


def reject_invalid_data(java):
    """真实 java -jar 解开 Spring 包装错误，保留拒绝的隔离文件原字节。"""
    codes = []
    with tempfile.TemporaryDirectory(prefix="leximeet-core-reject-") as owned:
        for name, version, expected in (
            ("old-schema", 13, "UNSUPPORTED_DATA_SCHEMA"),
            ("same-number-old-shape", 100, "DATA_STRUCTURE_MISMATCH"),
            ("unversioned-file", 0, "DATA_STRUCTURE_MISMATCH"),
            ("damaged-file", None, "DATA_CORRUPTED"),
        ):
            directory = Path(owned) / name
            directory.mkdir()
            file = directory / "leximeet.sqlite"
            if version is None:
                file.write_bytes(b"private-test-marker: damaged SQLite content")
            else:
                # sqlite3 的上下文只提交事务；显式关闭，Windows 才能清理临时数据库。
                with closing(sqlite3.connect(file)) as db:
                    with db:
                        db.execute("CREATE TABLE private_old_shape(marker TEXT)")
                        db.execute("INSERT INTO private_old_shape VALUES('private-test-marker')")
                        db.execute(f"PRAGMA user_version={version}")
            original = hashlib.sha256(file.read_bytes()).digest()
            token = secrets.token_urlsafe(36)
            result = subprocess.run(
                [str(java), "-jar", str(JAR), "--data-dir", str(directory),
                 "--token", token, "--profile", "test"],
                capture_output=True, text=True, encoding="utf-8", timeout=20,
            )
            if result.returncode != 1 or result.stdout or f"[{expected}]" not in result.stderr:
                raise RuntimeError("真实 Jar 未按预期拒绝隔离异常资料：" + name)
            if len(result.stderr.splitlines()) != 1 or "BeanCreationException" in result.stderr:
                raise RuntimeError("真实 Jar 没有输出唯一安全叶级诊断：" + name)
            if any(secret in result.stderr for secret in (token, str(directory), "private-test-marker", "SELECT", "INSERT")):
                raise RuntimeError("真实 Jar 错误输出泄露临时令牌、路径或正文")
            if hashlib.sha256(file.read_bytes()).digest() != original:
                raise RuntimeError("拒绝异常资料时修改了原文件：" + name)
            codes.append(expected)
    return codes


if __name__ == "__main__":
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise SystemExit("请设置 JAVA_HOME 为 JDK 21")
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    components = check_artifacts()
    smoke(java)
    startup_failures = reject_invalid_data(java)
    summary = {
        "status": "passed", "javaMajor": 21, "runtimeComponents": components,
        "jarSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
        "sourcesSha256": hashlib.sha256(SOURCES.read_bytes()).hexdigest(),
        "bomSha256": hashlib.sha256(BOM.read_bytes()).hexdigest(),
        "temporaryProfileRemoved": True,
        "startupFailureCases": startup_failures,
        "scope": "真实 Jar 启动、鉴权、读取、正式收藏、退休路由404、拒旧/坏文件安全诊断与隔离清理；不代表 Desktop UI/OS 通知验收",
    }
    (ROOT / "target/release-verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
