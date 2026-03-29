package com.databoss.sfbucwa.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UcwaAuthenticationException.class)
    public ProblemDetail handleAuthException(UcwaAuthenticationException ex) {
        log.error("UCWA Authentication hatası: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setTitle("UCWA Kimlik Doğrulama Hatası");
        pd.setType(URI.create("urn:sfb-ucwa:error:authentication"));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(UcwaApiException.class)
    public ProblemDetail handleApiException(UcwaApiException ex) {
        log.error("UCWA API hatası: {} (status={})", ex.getMessage(), ex.getStatusCode());
        HttpStatus status = ex.getStatusCode() > 0
                ? HttpStatus.resolve(ex.getStatusCode())
                : HttpStatus.BAD_GATEWAY;
        if (status == null) status = HttpStatus.BAD_GATEWAY;

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setTitle("UCWA API Hatası");
        pd.setType(URI.create("urn:sfb-ucwa:error:api"));
        pd.setProperty("timestamp", Instant.now());
        pd.setProperty("ucwaStatusCode", ex.getStatusCode());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Beklenmeyen hata", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Beklenmeyen bir hata oluştu");
        pd.setTitle("Sunucu Hatası");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
