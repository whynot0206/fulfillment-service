package com.why.fulfillment.commerce.common;

import java.util.List;

/**
 * 统一分页返回。
 *
 * @param items 当前页数据
 * @param page  页码，从 1 开始
 * @param size  每页条数
 * @param total 符合条件的总条数
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        return new PageResult<>(items, page, size, total);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0L);
    }
}
