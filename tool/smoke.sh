#!/usr/bin/env bash
# =============================================================================
# 端到端冒烟脚本：登录 → 建库（可选）→ 上传文件 → 轮询文档状态 → 打印解析结果
#
#   ./tool/smoke.sh samples/fixture.mp4            # 用默认账号上传到 1 号知识库
#   ./tool/smoke.sh samples/fixture.xlsx 2         # 指定知识库 ID
#   USERNAME=xxx PASSWORD=yyy ./tool/smoke.sh ...
#
# 依赖：curl、python（用来读 JSON）。服务需先起来：./tool/dev.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="${1:?用法: ./tool/smoke.sh <文件路径> [知识库ID]}"
KB_ID="${2:-0}"
BASE="${BASE:-http://127.0.0.1:8080}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin123}"

jget() { python -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

echo "== 登录 $USERNAME =="
LOGIN=$(curl -s --noproxy '*' -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
# 账号不存在就先注册（第一个注册的用户是 ADMIN；之后注册的默认是 MEMBER）
if ! printf '%s' "$LOGIN" | grep -q '"code":0'; then
  echo "  登录失败，尝试注册：$LOGIN"
  curl -s --noproxy '*' -X POST "$BASE/api/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"displayName\":\"smoke\"}" >/dev/null
  LOGIN=$(curl -s --noproxy '*' -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
fi
TOKEN=$(printf '%s' "$LOGIN" | jget "['data']['token']")
echo "token=${TOKEN:0:24}..."

# 没传知识库 ID（或传 0）时，用当前账号新建一个，避免 MEMBER 无权访问别人的库
if [[ -z "$KB_ID" || "$KB_ID" == "0" ]]; then
  KB_ID=$(curl -s --noproxy '*' -X POST "$BASE/api/kb" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"smoke-$(date +%s)\",\"description\":\"冒烟测试自动创建\",\"visibility\":\"PRIVATE\"}" \
    | jget "['data']['id']")
  echo "== 已自动创建知识库 kbId=$KB_ID =="
fi

echo "== 上传 $FILE 到知识库 $KB_ID =="
UP=$(curl -s --noproxy '*' -X POST "$BASE/api/kb/$KB_ID/documents" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$FILE")
echo "$UP"
DOC_ID=$(printf '%s' "$UP" | jget "['data']['docId']")
echo "docId=$DOC_ID"

echo "== 轮询状态 =="
for i in $(seq 1 40); do
  sleep 3
  ST=$(curl -s --noproxy '*' "$BASE/api/kb/documents/$DOC_ID" -H "Authorization: Bearer $TOKEN")
  STATUS=$(printf '%s' "$ST" | jget "['data']['status']")
  echo "  [${i}] status=$STATUS"
  # 只在终态停下：PENDING/PROCESSING/PARSING/EMBEDDING 都是中间态
  if [[ "$STATUS" == "INDEXED" || "$STATUS" == "FAILED" ]]; then
    echo "$ST" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('---');[print(k,'=',v) for k,v in d.items() if k in ('status','modality','chunkCount','pageCount','summary','errorMessage')]"
    break
  fi
done
