# =============================================================================
# DocMind 后端镜像：多阶段构建（Maven 构建 → 精简 JRE 运行）
# 构建期需要联网拉依赖；运行期只带 JRE。
# =============================================================================

# ---------- stage 1: build ----------
FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只拷 POM，利用 Docker 层缓存：依赖不变时跳过重新下载
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests -Dmaven.test.skip=true

# 从 fat jar 中提取分层，提升镜像层复用率
RUN java -Djarmode=layertools -jar target/docmind-backend-1.0.0.jar extract --destination target/extracted

# ---------- stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy

ENV LANG=C.UTF-8 \
    TZ=Asia/Shanghai \
    DOCMIND_SERVER_PORT=8080 \
    DOCMIND_SERVER_ADDRESS=0.0.0.0

RUN groupadd -g 10001 docmind \
 && useradd -u 10001 -g docmind -s /usr/sbin/nologin -m docmind \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      curl wget tesseract-ocr tesseract-ocr-chi-sim ffmpeg fonts-noto-cjk \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder --chown=docmind:docmind /build/target/extracted/dependencies/ ./
COPY --from=builder --chown=docmind:docmind /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=docmind:docmind /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=docmind:docmind /build/target/extracted/application/ ./

# 运行期数据目录（本地存储兜底、向量库快照等）
RUN mkdir -p /app/data && chown -R docmind:docmind /app/data

USER docmind
EXPOSE 8080

# 分层启动 + 容器感知；heap 由 JAVA_TOOL_OPTIONS 的 MaxRAMPercentage 控制
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "org.springframework.boot.loader.launch.JarLauncher"]
