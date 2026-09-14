package com.parcelrouting.batch;

public class BatchUploadTooLargeException extends RuntimeException {

    public BatchUploadTooLargeException(String message) {
        super(message);
    }
}
