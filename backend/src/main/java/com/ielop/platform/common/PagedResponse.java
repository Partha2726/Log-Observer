package com.ielop.platform.common;

import java.util.List;

/**
 * Generic paginated response envelope used across all paginated API endpoints.
 *
 * <p>Consistent pagination contract — clients always receive the
 * same pagination shape regardless of which resource they're querying.</p>
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PagedResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
