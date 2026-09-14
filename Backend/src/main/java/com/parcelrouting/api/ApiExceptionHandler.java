package com.parcelrouting.api;

import com.parcelrouting.service.ApprovalService;
import com.parcelrouting.batch.BatchUploadTooLargeException;
import com.parcelrouting.config.ConfigVersionNotFoundException;
import com.parcelrouting.config.ConfigVersionStateException;
import com.parcelrouting.config.ConfigVersionActivationException;
import com.parcelrouting.config.ConfigVersionRollbackException;
import com.parcelrouting.monitoring.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "Invalid request"
                : "Invalid request: " + exception.getBindingResult().getFieldErrors().get(0).getField()
                + " " + exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(new ApiErrorResponse(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson() {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("Malformed request JSON"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ApprovalService.ApprovalNotPendingException.class)
    public ResponseEntity<ApiErrorResponse> handleApprovalConflict(ApprovalService.ApprovalNotPendingException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiErrorResponse("Access denied"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(BatchUploadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(BatchUploadTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipartUploadTooLarge() {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiErrorResponse("Batch file exceeds the maximum allowed upload size"));
    }

    @ExceptionHandler(ConfigVersionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleConfigVersionNotFound(ConfigVersionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ConfigVersionStateException.class)
    public ResponseEntity<ApiErrorResponse> handleConfigVersionConflict(ConfigVersionStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ConfigVersionActivationException.class)
    public ResponseEntity<ApiErrorResponse> handleConfigVersionActivationConflict(ConfigVersionActivationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ConfigVersionRollbackException.class)
    public ResponseEntity<ApiErrorResponse> handleConfigVersionRollbackConflict(ConfigVersionRollbackException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationFailure(Exception exception) {
        LOGGER.error(
                "Unexpected application failure correlationId={} exceptionType={}",
                MDC.get(CorrelationIdFilter.MDC_KEY),
                exception.getClass().getName()
        );
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("Parcel submission could not be completed"));
    }
}
