#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
check_compose.py 的自检：故意把 compose / Dockerfile 改坏，确认校验器真的会报错。

只有「能抓到错误」的校验器才有价值，所以这个自检跟校验器本身一样重要。
每次改 check_compose.py 后都应该跑一遍，防止检查逻辑悄悄失效。

坑：读写一律走 bytes。用 Path.write_text() 在 Windows 上会把 \\n 翻译成 \\r\\n，
    还原后文件内容虽然"看起来一样"，字节却已经变了，而 git diff 因为 autocrlf
    归一化还显示不出来 —— 这种脏改动极难发现。

用法：
    python tool/check_compose_selftest.py
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ROOT / "docker-compose.yml"
DOCKERFILE = ROOT / "backend" / "Dockerfile"
PY = sys.executable

# (用例名, 目标文件, 原文, 替换文)
CASES: list[tuple[str, Path, str, str]] = [
    ("宿主相对路径拼错", COMPOSE,
     "./deploy/init:/docker-entrypoint-initdb.d:ro",
     "./deploy/init-typo:/docker-entrypoint-initdb.d:ro"),
    ("具名卷未在顶层声明", COMPOSE,
     "- pgdata:/var/lib/postgresql/data",
     "- pgdataX:/var/lib/postgresql/data"),
    ("depends_on 引用不存在的服务", COMPOSE,
     "      postgres:\n        condition: service_healthy\n",
     "      postgres:\n        condition: service_healthy\n"
     "      ghostsvc:\n        condition: service_healthy\n"),
    ("Dockerfile 把 .env 打进镜像", DOCKERFILE,
     "COPY",
     "COPY .env /app/.env\nCOPY"),
]


def run_checker(path: Path) -> tuple[int, str]:
    proc = subprocess.run(
        [PY, str(ROOT / "tool" / "check_compose.py"), str(path)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main() -> int:
    targets = {COMPOSE, DOCKERFILE}
    originals = {p: p.read_bytes() for p in targets}
    passed = 0

    try:
        for title, target, needle, replacement in CASES:
            raw = originals[target].decode("utf-8")
            if needle not in raw:
                print(f"  [SKIP]  {title}：锚点未命中，该场景没有真正被测到")
                continue
            target.write_bytes(raw.replace(needle, replacement, 1).encode("utf-8"))
            code, _out = run_checker(COMPOSE)
            if code != 0:
                print(f"  [PASS]  {title}：校验器正确报错")
                passed += 1
            else:
                print(f"  [FAIL]  {title}：校验器没报错（检查已失效！）")
    finally:
        for p, blob in originals.items():
            p.write_bytes(blob)
        print("\n被改动的文件已按原始字节还原")

    print(f"自检结果：{passed}/{len(CASES)} 种故障场景被正确拦截")
    return 0 if passed == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())
