package com.TreDL.ecommerce.dto;

import java.math.BigDecimal;

public record AdminProductRequest(String nome, String descrizione, BigDecimal prezzo, String categoria, String image, BigDecimal stock) {
    public AdminProductRequest(String nome, String descrizione, BigDecimal prezzo, String categoria, String image) {
        this(nome, descrizione, prezzo, categoria, image, null);
    }
}
