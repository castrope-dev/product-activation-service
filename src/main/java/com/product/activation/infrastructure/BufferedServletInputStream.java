package com.product.activation.infrastructure;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.ByteArrayInputStream;

final class BufferedServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream input;

    BufferedServletInputStream(byte[] body) {
        input = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
        return input.read();
    }

    @Override
    public boolean isFinished() {
        return input.available() == 0;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException();
    }
}
