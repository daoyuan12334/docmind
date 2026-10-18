package com.docmind.infra.storage;

import com.docmind.common.ErrorCode;

import java.io.InputStream;

/** 对象存储端口。生产用 MinIO/S3，开发用本地磁盘。 */
public interface StoragePort {

    String put(String key, InputStream in, long size, String contentType);

    InputStream get(String key);

    void delete(String key);

    boolean exists(String key);

    String type();

    default com.docmind.common.BizException fail(String msg) {
        return new com.docmind.common.BizException(ErrorCode.STORAGE_ERROR, msg);
    }
}
