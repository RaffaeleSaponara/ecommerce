package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.service.AdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService admin;
    public AdminController(AdminService admin) { this.admin = admin; }

    @GetMapping("/dashboard") public AdminDashboardDTO dashboard() { return admin.dashboard(); }
    @GetMapping("/products") public List<ProductDTO> products() { return admin.products(); }
    @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED)
    public ProductDTO create(@RequestBody AdminProductRequest request) { return admin.create(request); }
    @PutMapping("/products/{id}")
    public ProductDTO update(@PathVariable long id, @RequestBody AdminProductRequest request) { return admin.update(id, request); }
    @GetMapping("/orders") public List<OrderDTO> orders() { return admin.orders(); }
    @GetMapping("/orders/{id}") public OrderDTO order(@PathVariable long id) { return admin.order(id); }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(Exception error) {
        return Map.of("message", error instanceof IllegalArgumentException ? error.getMessage() : "Richiesta non valida");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> notFound(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }
}
