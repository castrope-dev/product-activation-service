package com.product.activation.api.dto.request;

import jakarta.validation.constraints.*;

public record CreateRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String externalId,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String customerId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+") String productCode) {
}
