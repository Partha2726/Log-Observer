package com.ielop.platform.common;

import java.util.List;

/**
 * Page envelope of spec section 8.7: {@code page} is 0-based, {@code size} at most 100.
 * Elasticsearch-backed lists cap {@code totalElements} at 10,000 and then set
 * {@code totalIsLowerBound}. (The salvaged user API keeps its {@link PagedResponse}.)
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages, boolean totalIsLowerBound) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total, boolean lowerBound) {
        int pages = size == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new PageResponse<>(List.copyOf(items), page, size, total, pages, lowerBound);
    }
}
