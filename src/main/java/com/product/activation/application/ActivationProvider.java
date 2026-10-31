package com.product.activation.application;

import com.product.activation.domain.ActivationRequest;
import com.product.activation.application.result.Outcome;

public interface ActivationProvider {
    Outcome activate(ActivationRequest request);
}
