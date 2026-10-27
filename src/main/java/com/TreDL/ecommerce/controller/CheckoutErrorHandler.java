package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.CheckoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes = CartController.class)
public class CheckoutErrorHandler {
    @ExceptionHandler(CheckoutException.class)
    public ResponseEntity<Map<String, String>> checkoutError(CheckoutException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("error", exception.getMessage()));
    }
}
