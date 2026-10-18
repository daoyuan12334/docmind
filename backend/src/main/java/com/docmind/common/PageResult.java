package com.docmind.common;

import java.util.List;

public record PageResult<T>(List<T> rows, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> rows, long total, int page, int size) {
        return new PageResult<>(rows, total, page, size);
    }

    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
