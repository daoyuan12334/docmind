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

# (用例名, 目标文件, 原文, 替换文, 期望出现在报错里的关键字)
CASES: list[tuple[str, Path, str, str, str]] = [
    ("宿主相对路径拼错", COMPOSE,
     "./deploy/init:/docker-entrypoint-initdb.d:ro",
     "./deploy/init-typo:/docker-entrypoint-initdb.d:ro",
     "宿主路径不存在"),
    ("具名卷未在顶层声明", COMPOSE,
     "- pgdata:/var/lib/postgresql/data",
     "- pgdataX:/var/lib/postgresql/data",
     "未在顶层"),
    ("depends_on 引用不存在的服务", COMPOSE,
     "      postgres:\n        condition: service_healthy\n",
     "      postgres:\n        condition: service_healthy\n"
     "      ghostsvc:\n        condition: service_healthy\n",
     "不存在的服务"),
    ("Dockerfile 把 .env 打进镜像", DOCKERFILE,
     "COPY",
     "COPY .env /app/.env\nCOPY",
     "敏感文件"),
]

# 与 check_compose.py 约定的退出码
EXIT_OK = 0
EXIT_PROBLEM = 1
EXIT_UNRUNNABLE = 2


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
    failed = 0

    # 前置检查：确认校验器自身可执行。缺依赖时它现在会返回 2，
    # 以前是直接崩，而这个自检只看「退出码非 0」，所以曾给出过 4/4 的假绿。
    base_code, base_out = run_checker(COMPOSE)
    if base_code == EXIT_UNRUNNABLE:
        print("  [ABORT] 校验器无法运行（依赖缺失或环境异常），自检无意义：")
        print(base_out.strip()[:500])
        return 1
    if base_code != EXIT_OK:
        print(f"  [ABORT] 原始 compose 文件本身就没通过校验（退出码 {base_code}），"
              f"先修好 compose 再跑自检：")
        print(base_out.strip()[:500])
        return 1
    print("  [PRE]   原始 compose 文件校验通过，可以开始注入故障\n")

    try:
        for title, target, needle, replacement, expect in CASES:
            raw = originals[target].decode("utf-8")
            if needle not in raw:
                print(f"  [SKIP]  {title}：锚点未命中，该场景没有真正被测到")
                failed += 1
                continue
            target.write_bytes(raw.replace(needle, replacement, 1).encode("utf-8"))
            code, out = run_checker(COMPOSE)

            if code == EXIT_UNRUNNABLE:
                # 崩溃 ≠ 拦截成功，这是本自检存在的意义之一
                print(f"  [FAIL]  {title}：校验器自身崩溃，无法判定：")
                print("          " + out.strip().splitlines()[0][:160])
                failed += 1
            elif code == EXIT_PROBLEM:
                if expect and expect not in out:
                    print(f"  [FAIL]  {title}：报错了，但文案里没有预期关键字「{expect}」，"
                          f"可能是被别的检查误伤")
                    failed += 1
                else:
                    print(f"  [PASS]  {title}：校验器正确报错（含「{expect}」）")
                    passed += 1
            else:
                print(f"  [FAIL]  {title}：校验器没报错（检查已失效！）")
                failed += 1
    finally:
        for p, blob in originals.items():
            p.write_bytes(blob)
        print("\n被改动的文件已按原始字节还原")

    print(f"自检结果：{passed}/{len(CASES)} 种故障场景被正确拦截")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
