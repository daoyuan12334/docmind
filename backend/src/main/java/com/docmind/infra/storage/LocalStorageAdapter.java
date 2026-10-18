package com.docmind.infra.storage;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** 本地磁盘存储适配器（dev）。生产请切到 MinIO，避免多实例时文件不一致。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "docmind.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageAdapter implements StoragePort {

    private final Path root;

    public LocalStorageAdapter(AppProperties props) {
        this.root = Paths.get(props.getStorage().getLocalRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + this.root, e);
        }
        log.info("本地存储根目录：{}", this.root);
    }

    @Override
    public String put(String key, InputStream in, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "写入本地存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "读取本地存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public String type() {
        return "local";
    }

    /** 归一化后校验，避免 key 里出现 ../ 造成路径穿越。 */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "非法存储路径: " + key);
        }
        return target;
    }
}
