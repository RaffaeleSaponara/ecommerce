package com.TreDL.ecommerce.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import java.util.Map;

// Multipart parsing can fail before Spring has selected a controller.
@RestControllerAdvice
public class ImageMultipartErrorHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(Map.of("message", "Immagine troppo grande (massimo 5 MiB)"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> invalid(MultipartException error) {
        return ResponseEntity.badRequest().body(Map.of("message", "Richiesta multipart non valida"));
    }
}
