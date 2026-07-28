package com.ajt.backend.domain.document.api;

public class DocumentUploadValidationException extends IllegalArgumentException {

    public DocumentUploadValidationException(String message) {
        super(message);
    }
}
