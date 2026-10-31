package com.product.activation.api.dto.response;

import java.util.List;

public record PageView(List<RequestView> content, int page, int size, long totalElements, int totalPages) {
}
