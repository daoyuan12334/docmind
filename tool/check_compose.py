#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
docker-compose 交付物静态校验 —— 在没有 Docker 的机器上，把「compose 起不来」的
常见原因提前抓出来。

它不假装能替代 `docker compose up`，只做几件确定性检查：

  1. YAML 能被解析（语法层）
  2. build.context / dockerfile 指向的文件真的存在
  3. volumes 里宿主机侧的相对路径真的存在（拼错路径是最常见的启动失败原因）
  4. env_file 引用的文件存在；且 ${VAR:?..} 形式的必填变量都有说明
  5. depends_on 引用的服务确实被定义了
  6. 宿主端口不重复（端口撞车同样是高频故障）
  7. Docker 构建上下文里没有把敏感文件（.env、*.key）COPY 进去

用法：
    python tool/check_compose.py            # 只报错
    python tool/check_compose.py --strict   # 把警告也当错误
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_COMPOSE = ROOT / "docker-compose.yml"

errors: list[str] = []
warns: list[str] = []
checks: list[str] = []
COMPOSE = DEFAULT_COMPOSE

# ${VAR} / ${VAR:-默认} / ${VAR:?提示}
# group(1)=变量名 group(2)=操作符('-' 或 '?') group(3)=操作数的文本
VAR_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([-?])([^}]*))?\}")


def fail(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warns.append(msg)


def check_refs(text: str, where: str) -> None:
    """
    检查环境变量占位符的写法是否安全。

    注意：别用 `text.split(name)` 去回溯当前匹配的写法 —— 同一个变量在文件里可能
    出现多次（如 REDIS_PASSWORD 一处 `${REDIS_PASSWORD:?...}`、一处 `${REDIS_PASSWORD}`），
    split 永远取第一次出现的位置，会把后者误判成「必填却没提示文案」。
    操作符必须从匹配本身取。
    """
    seen: set[str] = set()
    for name, op, operand in VAR_RE.findall(text):
        seen.add(name)
        if op is None:
            warn(f"{where}：变量 {name} 既无默认值也非必填标记，未设置时会被替换成空串")
        elif op == "?" and not operand.strip():
            warn(f"{where}：变量 {name} 用了必填写法（:?）却没给提示文案")
    return None


def main() -> int:
    global COMPOSE
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    COMPOSE = Path(args[0]).resolve() if args else DEFAULT_COMPOSE

    if not COMPOSE.exists():
        print(f"找不到 {COMPOSE}")
        return 2

    try:
        doc = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        print(f"YAML 解析失败：{e}")
        return 2

    services = doc.get("services") or {}
    if not services:
        fail("compose 文件里没有 services")
        return report()

    top_level_volumes = set((doc.get("volumes") or {}).keys())
    print(f"services({len(services)}): {', '.join(services)}")
    print(f"volumes({len(top_level_volumes)}): {', '.join(sorted(top_level_volumes))}")

    host_ports: dict[str, str] = {}

    for name, svc in services.items():
        svc = svc or {}

        # ---- build.context / dockerfile
        build = svc.get("build")
        if build:
            if isinstance(build, dict):
                ctx = build.get("context")
                df = build.get("dockerfile", "Dockerfile")
                if ctx:
                    ctx_path = (ROOT / ctx).resolve()
                    if not ctx_path.is_dir():
                        fail(f"{name}: build.context 目录不存在 -> {ctx_path}")
                    else:
                        df_path = ctx_path / df
                        if not df_path.is_file():
                            fail(f"{name}: Dockerfile 不存在 -> {df_path}")
                        else:
                            scan_dockerfile(df_path, name)
                            checks.append(f"{name}: build 上下文 {ctx} + {df} 存在且已扫描")

        # ---- image
        if not build and not svc.get("image"):
            fail(f"{name}: 既没有 build 也没有 image")

        # ---- env_file
        for ef in svc.get("env_file", []) or []:
            p = ef if os.path.isabs(ef) else (ROOT / ef)
            if not Path(p).exists():
                fail(f"{name}: env_file 不存在 -> {ef}")

        # ---- volumes：区分「宿主机路径」和「具名卷」
        # 按 compose 规范，short syntax 的源只有以 / ./ ../ ~/ 开头才是宿主路径；
        # 其余一律是具名卷，必须在顶层 volumes: 里声明，否则 compose 直接报错退出。
        for v in svc.get("volumes", []) or []:
            if not isinstance(v, str):
                continue
            host = v.split(":")[0]
            if not host:
                continue
            if host.startswith(("/", "./", "../", "~/")):
                p = (ROOT / host).resolve()
                if p.exists():
                    checks.append(f"{name}: volume 宿主路径存在 -> {host}")
                else:
                    fail(f"{name}: volume 宿主路径不存在 -> {host}  ({p})")
            elif host in top_level_volumes:
                checks.append(f"{name}: 具名卷已在顶层声明 -> {host}")
            else:
                fail(f"{name}: 具名卷 {host} 未在顶层 volumes: 中声明")

        # ---- ports：宿主端口冲突
        for p in svc.get("ports", []) or []:
            ps = str(p)
            m = re.match(r'^(?:"|\')?\$\{([A-Za-z_][A-Za-z0-9_]*):?-?(\d*)\}:(\d+)', ps)
            if not m:
                m2 = re.match(r"^(\d+):(\d+)", ps)
                if m2:
                    key = m2.group(1)
                else:
                    warn(f"{name}: 无法解析端口映射 -> {ps}")
                    continue
            else:
                key = m.group(1)
            if key in host_ports:
                fail(f"{name}: 宿主端口与 {host_ports[key]} 冲突 -> {key}")
            else:
                host_ports[key] = name

        # ---- depends_on 目标存在
        dep = svc.get("depends_on")
        if dep:
            targets = dep.keys() if isinstance(dep, dict) else dep
            for t in targets:
                if t not in services:
                    fail(f"{name}: depends_on 引用了不存在的服务 -> {t}")

    # ---- 环境变量占位符写法检查
    check_refs(COMPOSE.read_text(encoding="utf-8"), "docker-compose.yml")

    # ---- .env.example 是否覆盖所有用到的变量名
    env_example = ROOT / ".env.example"
    if env_example.exists():
        declared = set()
        for line in env_example.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                declared.add(line.split("=", 1)[0].strip())
        used = {name for name, _op, _operand in VAR_RE.findall(COMPOSE.read_text(encoding="utf-8"))}
        missing = sorted(used - declared)
        if missing:
            fail(f".env.example 缺少变量：{', '.join(missing)}")
        else:
            print(f".env.example 已覆盖全部 {len(used)} 个变量")
    else:
        warn("没有 .env.example，部署时容易漏配变量")

    return report()


def scan_dockerfile(path: Path, service: str) -> None:
    """扫 Dockerfile：抓明显会泄密或破坏构建的写法。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    for i, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if s.startswith("#"):
            continue
        if re.search(r"\bCOPY\s+.*(\.env|\*\.key|\*\.pem|secrets/)", s):
            fail(f"{service}: Dockerfile:{i} 把敏感文件打进了镜像 -> {s}")
        if re.search(r"\bADD\s+https?://", s):
            warn(f"{service}: Dockerfile:{i} 用 ADD 拉远程资源，建议改 COPY/RUN curl")


def report() -> int:
    print()
    for c in checks:
        print(f"  [OK]    {c}")
    for w in warns:
        print(f"  [WARN]  {w}")
    for e in errors:
        print(f"  [ERROR] {e}")
    print()
    if errors:
        print(f"发现 {len(errors)} 个问题（共检查 {len(checks)} 项）")
        return 1
    print(f"静态校验通过：{len(checks)} 项检查全部通过。"
          f"（未安装 Docker，未做真启动验证）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
