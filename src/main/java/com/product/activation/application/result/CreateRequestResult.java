package com.product.activation.application.result;

import com.product.activation.domain.ActivationRequest;

public record CreateRequestResult(ActivationRequest request, boolean inserted) {
}
