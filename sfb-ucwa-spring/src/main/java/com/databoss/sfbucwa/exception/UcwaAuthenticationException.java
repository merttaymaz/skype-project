package com.databoss.sfbucwa.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UcwaAuthenticationException extends RuntimeException {
    public UcwaAuthenticationException(String message) {
        super(message);
    }
    public UcwaAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
