#!/usr/bin/env python3
"""公开 Markdown 的相对文件链接检查；不访问网络、不读取外部讨论。"""

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

# Windows runner 的重定向输出可能默认使用 cp1252，显式保留中文验证结果。
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
files = sorted(list(ROOT.glob("*.md")) + list((ROOT / "docs").rglob("*.md")))
errors = []
links = 0
for file in files:
    text = file.read_text(encoding="utf-8")
    if re.search(r"/Users/|/private/var/folders/|dcs/opensource/|docs/\.chat", text) and file.name != "AGENTS.md":
        errors.append(f"{file.relative_to(ROOT)}: 包含工作区私有路径")
    for target in re.findall(r"!?\[[^\]]*\]\(([^)]+)\)", text):
        target = target.split(' "', 1)[0].strip().strip("<>")
        if target.startswith("#") or urlsplit(target).scheme:
            continue
        path = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not path:
            continue
        links += 1
        resolved = (file.parent / path).resolve()
        if not resolved.is_relative_to(ROOT) or not resolved.exists():
            errors.append(f"{file.relative_to(ROOT)}: 缺少仓内目标 {target}")
if errors:
    raise SystemExit("\n".join(errors))
print(f"公开文档 {len(files)} 篇，相对文件链接 {links} 项通过；不检查外部网络和页内锚点。")
