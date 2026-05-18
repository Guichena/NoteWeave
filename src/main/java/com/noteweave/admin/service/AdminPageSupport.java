package com.noteweave.admin.service;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

final class AdminPageSupport {

    private AdminPageSupport() {
    }

    static Pageable buildPageable(int page, int pageSize, String sortValue, Set<String> allowedSortFields, Sort defaultSort) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        return PageRequest.of(safePage - 1, safePageSize, parseSort(sortValue, allowedSortFields, defaultSort));
    }

    static String toSortExpression(Sort sort) {
        Sort.Order order = sort.stream().findFirst()
                .orElse(Sort.Order.by("createdAt").with(Sort.Direction.DESC));
        return order.getProperty() + "," + order.getDirection().name().toLowerCase();
    }

    private static Sort parseSort(String sortValue, Set<String> allowedSortFields, Sort defaultSort) {
        if (sortValue == null || sortValue.isBlank()) {
            return defaultSort;
        }
        String[] parts = sortValue.split(",");
        String property = parts[0].trim();
        if (!allowedSortFields.contains(property)) {
            return defaultSort;
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.DESC;
        }
        return Sort.by(direction, property);
    }
}
