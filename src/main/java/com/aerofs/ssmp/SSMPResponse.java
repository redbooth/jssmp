package com.aerofs.ssmp;

import javax.annotation.Nullable;

public class SSMPResponse {
    public static final int OK = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int NOT_FOUND = 404;
    public static final int NOT_ALLOWED = 405;
    public static final int CONFLICT = 409;
    public static final int NOT_IMPLEMENTED = 501;

    public final int code;
    public final @Nullable String payload;

    public SSMPResponse(int code, @Nullable String payload) {
        if (code < 0 || code > 999) {
            throw new IllegalArgumentException("invalid response code: " + code);
        }
        this.code = code;
        this.payload = payload;
    }

    @Override
    public String toString() {
        return Integer.toString(code) + " " + payload;
    }
}
