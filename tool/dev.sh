#!/usr/bin/env bash
# =============================================================================
# 本地一键启动（dev profile）：H2 + 内存向量库 + 本地磁盘 + 内存消息总线
# 零外部依赖，用来快速验证功能链路。
#
#   ./tool/dev.sh                     # 启动（纯 dev，零外部依赖）
#   ./tool/dev.sh --build             # 先打包再启动
#   ./tool/dev.sh --profile dev-redis # 叠加 dev-redis：把缓存换成真 Redis
#                                     # （需先起 redis-server，本机免 Docker 验证限流/会话窗口）
#
# 注意：本机环境变量里存在 SERVER__PORT / SERVER__HOST（沙箱注入），
# Spring Boot 的宽松绑定会把它当成 server.port/server.address，导致端口被劫持。
# 这里显式清空并强制 --server.port=8080。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_HOME="${JDK_HOME:-C:/Program Files/Java/jdk-21}"
JAVA_BIN="$JDK_HOME/bin/java.exe"
# Git Bash 的 pwd 是 /c/... 形式，Windows 的 java.exe 不认，必须转成 C:/...
JAR="$(cd "$ROOT/backend/target" && pwd -W)/docmind-backend-1.0.0.jar"

EXTRA_PROFILE="dev"
while [[ $# -gt 0 ]]; do
  case "$1" in
    # 注意：mvn.sh 只是 plexus 启动器（不是带 -f 的包装器），必须从 pom.xml 所在目录执行，
    # 否则报 "no POM in this directory"。所以这里要 cd 到 backend 再跑。
    --build)   (cd "$ROOT/backend" && "$ROOT/tool/mvn.sh" -B -ntp package -DskipTests) ;;
    --profile) shift; EXTRA_PROFILE="dev,${1:-}" ;;
  esac
  shift || true
done

if [[ ! -f "$JAR" ]]; then
  echo "找不到 $JAR，请先执行：./tool/dev.sh --build" >&2
  exit 1
fi

# 从 .env 读取 API Key（若存在）
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source <(grep -E '^(DASHSCOPE_API_KEY|AI_|DOCMIND_|JWT_|FFMPEG_PATH)' "$ROOT/.env" || true)
  set +a
fi

# 视频抽帧依赖 ffmpeg。Windows 上若 PATH 里没有，可在 .env 里配 FFMPEG_PATH，
# 或直接用 npm 装一份：npm i @ffmpeg-installer/ffmpeg
if [[ -z "${FFMPEG_PATH:-}" ]]; then
  FFMPEG_CANDIDATE="$HOME/.workbuddy/binaries/ffm/node_modules/@ffmpeg-installer/win32-x64/ffmpeg.exe"
  if [[ -f "$FFMPEG_CANDIDATE" ]]; then
    export FFMPEG_PATH="$FFMPEG_CANDIDATE"
  else
    export FFMPEG_PATH="ffmpeg"
  fi
fi

# Git Bash 的路径是 /c/... 形式，Java 的 ProcessBuilder 走 Windows CreateProcess，
# 认不出这种路径（报 CreateProcess error=2）。统一转成 C:/... 再传给 JVM。
if [[ "$FFMPEG_PATH" == /* ]]; then
  FFMPEG_PATH="$(cd "$(dirname "$FFMPEG_PATH")" 2>/dev/null && pwd -W)/$(basename "$FFMPEG_PATH")"
fi
export FFMPEG_PATH
echo "FFMPEG_PATH=$FFMPEG_PATH"

echo "启动 DocMind（profiles=$EXTRA_PROFILE）→ http://127.0.0.1:8080"
cd "$ROOT/backend"
SERVER__PORT= SERVER__HOST= \
exec "$JAVA_BIN" -Xmx700m -jar "$JAR" \
  --spring.profiles.active="$EXTRA_PROFILE" \
  --server.port=8080 \
  --server.address=0.0.0.0
