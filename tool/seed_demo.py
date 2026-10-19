#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演示数据灌入：建 4 个主题知识库，把 samples 下的素材按主题归位。

    python tool/seed_demo.py

幂等：同名知识库会复用，不会重复创建；重复内容文档后端会按 checksum 判重。
注意：文档向量化会调用模型接口，产生少量费用。
"""
import hashlib
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("DOCMIND_BASE", "http://127.0.0.1:8080")
USERNAME = os.environ.get("DOCMIND_USER", "admin")
PASSWORD = os.environ.get("DOCMIND_PASS", "admin123")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLES = os.path.join(ROOT, "samples")

# 文档内容改过之后再跑本脚本，同一文件名会先删旧版再传新版（默认开启）。
# 设为 0 则只补缺失的文档，已有的不动。
REPLACE = os.environ.get("DOCMIND_SEED_REPLACE", "1") not in ("0", "false", "no")

# 强制重灌：忽略 checksum 比对，全部删旧传新。
# 解析器/切分策略改过之后必须用它 —— 文档内容没变，但索引是旧逻辑产出的，
# 不重建就永远看不到修复效果（这一点踩过：改完标题解析，问答依旧答错，
# 因为库里还是老切片）。
FORCE = os.environ.get("DOCMIND_SEED_FORCE", "0") not in ("0", "false", "no")


def request(method, path, token=None, body=None, headers=None):
    url = BASE + path
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
        else:
            data = body
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith("{") else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:300]


def login():
    status, res = request("POST", "/api/auth/login",
                          body={"username": USERNAME, "password": PASSWORD})
    if status != 200:
        sys.exit("登录失败 HTTP %s: %s" % (status, res))
    return res["data"]["token"]


def kb_id(token, name, desc):
    status, res = request("GET", "/api/kb", token=token)
    for kb in (res.get("data") or []):
        if kb.get("name") == name:
            return kb["id"], False
    status, res = request("POST", "/api/kb", token=token,
                          body={"name": name, "description": desc, "visibility": "PRIVATE"})
    if status != 200:
        sys.exit("创建知识库失败 %s: %s" % (status, res))
    return res["data"]["id"], True


def upload(token, kbid, file_path):
    """multipart/form-data 手写：避免额外依赖，中文文件名按 utf-8 编码。"""
    name = os.path.basename(file_path)
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    with open(file_path, "rb") as f:
        content = f.read()
    boundary = "----docmind" + uuid.uuid4().hex
    body = b""
    body += ("--%s\r\n" % boundary).encode()
    body += ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode("utf-8")
    body += ("Content-Type: %s\r\n\r\n" % ctype).encode()
    body += content
    body += ("\r\n--%s--\r\n" % boundary).encode()
    status, res = request(
        "POST", "/api/kb/%d/documents" % kbid, token=token, body=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    flag = ""
    if isinstance(res, dict) and res.get("data"):
        if res["data"].get("duplicate"):
            flag = "（重复，已跳过）"
    print("    %-26s HTTP %s %s" % (name, status, flag))
    return status


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(65536), b""):
            h.update(block)
    return h.hexdigest()


def list_docs(token, kbid):
    status, res = request("GET", "/api/kb/%d/documents?size=100" % kbid, token=token)
    raw = res.get("data")
    if isinstance(raw, list):
        return raw
    if isinstance(raw, dict):
        return raw.get("content") or raw.get("items") or []
    return []


def seed(token, title, desc, files):
    print("\n== %s ==" % title)
    kbid, created = kb_id(token, title, desc)
    print("  知识库 id=%s%s" % (kbid, "（新建）" if created else "（已存在，复用）"))
    # 同名文档先删旧版再传新版：否则改过内容的文件会作为「另一份文档」并存，
    # 检索时两代内容互相干扰、引用来源也重复。
    # 注意：列表接口里的文件名字段是 name，不是 fileName
    existing = {}
    for d in list_docs(token, kbid):
        nm = d.get("name") or d.get("fileName")
        if nm:
            existing[nm] = d
    for rel in files:
        p = os.path.join(SAMPLES, rel)
        if not os.path.isfile(p):
            print("    跳过（文件不存在）: %s" % rel)
            continue
        name = os.path.basename(p)
        cur = existing.get(name)
        if cur:
            # 用 checksum 精确比对：内容没变就别重灌，向量化是要花钱的
            if not FORCE and cur.get("checksum") and cur["checksum"] == sha256_of(p):
                print("    %-26s 已是最新，跳过" % name)
                continue
            if not (REPLACE or FORCE):
                print("    %-26s 已存在且内容不同（未开启替换，跳过）" % name)
                continue
            st, _ = request("DELETE", "/api/kb/documents/%d" % cur["id"], token=token)
            print("    %-26s %s，删除旧版 HTTP %s"
                  % (name, "强制重灌" if FORCE else "内容已变更", st))
        upload(token, kbid, p)


def main():
    token = login()
    print("登录成功 → %s" % BASE)

    seed(token, "企业制度中心", "人事、行政、财务等内部制度", [
        "docmind-test.md",
        "制度宣讲.pptx",
        "报销标准表.xlsx",
        os.path.join("demo", "员工手册.md"),
        os.path.join("demo", "考勤与假期管理办法.md"),
        os.path.join("demo", "信息安全管理制度.md"),
    ])
    seed(token, "产品与客服", "产品手册、常见问题、客服规范", [
        os.path.join("demo", "产品手册.md"),
        os.path.join("demo", "常见问题FAQ.md"),
        os.path.join("demo", "客户服务规范.md"),
        os.path.join("demo", "版本发布记录.md"),
        os.path.join("demo", "客户成功案例集.md"),
        os.path.join("demo", "服务等级协议SLA.md"),
    ])
    seed(token, "技术研发", "架构设计与部署运维文档", [
        os.path.join("demo", "系统架构说明.md"),
        os.path.join("demo", "部署运维指南.md"),
        os.path.join("demo", "API接口规范.md"),
        os.path.join("demo", "数据库设计说明.md"),
        os.path.join("demo", "故障复盘报告.md"),
        os.path.join("demo", "编码规范与评审清单.md"),
        "fixture.pdf",
    ])
    seed(token, "多模态素材库", "图片、扫描件、音频、视频、表格", [
        "sample.png",
        "fixture-scanned.pdf",
        "fixture.wav",
        "fixture.mp4",
        "fixture.xlsx",
    ])

    print("\n等待异步索引（解析 + 向量化）...")
    wait_indexed(token, timeout=900)

    print("\n=== 结果汇总 ===")
    status, res = request("GET", "/api/kb", token=token)
    print("  %-16s %-6s %-6s %-8s" % ("知识库", "文档", "切片", "状态"))
    for kb in (res.get("data") or []):
        print("  %-16s %-6s %-6s" % (kb.get("name"), kb.get("docCount", kb.get("documentCount", "?")),
                                     kb.get("chunkCount", "?")))


def wait_indexed(token, timeout=900):
    """轮询文档状态直到全部终态（INDEXED / FAILED），避免固定 sleep 不够或干等。"""
    deadline = time.time() + timeout
    last_pending = -1
    while time.time() < deadline:
        pending = 0
        failed = 0
        for kb in (request("GET", "/api/kb", token=token)[1].get("data") or []):
            status, res = request("GET", "/api/kb/%d/documents?size=100" % kb["id"], token=token)
            raw = res.get("data")
            if isinstance(raw, list):
                docs = raw
            elif isinstance(raw, dict):
                docs = raw.get("content") or raw.get("items") or []
            else:
                docs = []
            for d in docs:
                st = d.get("status")
                if st in ("PENDING", "PARSING", "CHUNKING", "EMBEDDING"):
                    pending += 1
                elif st == "FAILED":
                    failed += 1
        if pending != last_pending:
            print("    进行中 %d 篇，失败 %d 篇" % (pending, failed))
            last_pending = pending
        if pending == 0:
            print("    全部进入终态（失败 %d 篇）" % failed)
            return
        time.sleep(10)
    print("    超时：仍有文档未完成，请到文档管理页查看")


if __name__ == "__main__":
    main()
