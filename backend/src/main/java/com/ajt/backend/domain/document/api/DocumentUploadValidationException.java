package com.ajt.backend.domain.document.api;

public class DocumentUploadValidationException extends IllegalArgumentException {

    private final String field;

    public DocumentUploadValidationException(String message) {
        this("files", message);
    }

    public DocumentUploadValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
