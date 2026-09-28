package com.friendspharma.app.api;

import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated list wrapper.
 */
@Getter
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean first;
    private final boolean last;

    private PageResponse(List<T> content, int page, int size,
                         long totalElements, int totalPages,
                         boolean first, boolean last) {
        this.content       = content;
        this.page          = page;
        this.size          = size;
        this.totalElements = totalElements;
        this.totalPages    = totalPages;
        this.first         = first;
        this.last          = last;
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(),
                page.isFirst(), page.isLast());
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return of(page);
    }

    public static <T, S> PageResponse<T> from(Page<S> page, List<T> mappedContent) {
        return new PageResponse<>(
                mappedContent, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(),
                page.isFirst(), page.isLast());
    }
}
