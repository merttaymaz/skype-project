package com.databoss.sfbucwa.exception;

public class UcwaApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public UcwaApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public UcwaApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
}
