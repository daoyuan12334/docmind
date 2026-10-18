#!/usr/bin/env bash
# DocMind Maven 包装器
# 用途：绕开本机 Git Bash 下 mvn 脚本的 mingw 路径转换缺陷（JVM 需要 Windows 风格 classpath），
# 并固定使用隔离安装的 Maven + JDK，不依赖全局环境。
set -euo pipefail

MVN_HOME="${MVN_HOME:-C:/Users/Admin/.workbuddy/binaries/maven/apache-maven-3.9.11}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Java/jdk-21}"
JAVA_BIN="$JDK_HOME/bin/java.exe"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -W 2>/dev/null || pwd)"

if [[ ! -x "$JAVA_BIN" ]]; then
  echo "找不到 JDK: $JAVA_BIN" >&2
  exit 1
fi

exec "$JAVA_BIN" \
  -classpath "$MVN_HOME/boot/plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=$MVN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MVN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR" \
  ${MAVEN_EXTRA_OPTS:-} \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
