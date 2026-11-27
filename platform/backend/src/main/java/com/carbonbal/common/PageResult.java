package com.carbonbal.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

public record PageResult<T>(long total, List<T> rows, int page, int size) {
    public static <T> PageResult<T> of(Page<T> p) {
        return new PageResult<>(p.getTotal(), p.getRecords(), (int) p.getCurrent(), (int) p.getSize());
    }

    public static <T> PageResult<T> of(List<T> rows) {
        return new PageResult<>(rows.size(), rows, 1, rows.size());
    }
}
