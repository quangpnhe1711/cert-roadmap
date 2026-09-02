package com.certcopilot.domain.identity;

/** Authentication failure with a stable code the client localises. */
public class AuthException extends RuntimeException {

    private final String code;

    public AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
