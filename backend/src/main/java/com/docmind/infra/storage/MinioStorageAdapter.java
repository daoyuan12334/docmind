package com.docmind.infra.storage;

import com.docmind.common.BizException;
import com.docmind.common.ErrorCode;
import com.docmind.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** MinIO 对象存储适配器（prod）。桶不存在时自动创建。 */
@Component
@ConditionalOnProperty(name = "docmind.storage.type", havingValue = "minio")
public class MinioStorageAdapter implements StoragePort {

    private final MinioClient client;
    private final String bucket;

    public MinioStorageAdapter(AppProperties props) {
        AppProperties.Storage cfg = props.getStorage();
        this.bucket = cfg.getBucket();
        this.client = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
    }

    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String put(String key, InputStream in, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
            return key;
        } catch (Exception e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "上传到 MinIO 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "从 MinIO 读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String type() {
        return "minio";
    }
}
