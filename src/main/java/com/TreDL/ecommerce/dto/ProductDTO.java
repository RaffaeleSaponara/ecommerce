package com.TreDL.ecommerce.dto;

import com.TreDL.ecommerce.model.Products;
import lombok.Data;


public class ProductDTO {

    private Long id;
    private String nome;
    private String descrizione;
    private double prezzo;
    private String categoria;
    private String image;
    private int quantita;


    public static ProductDTO convertToDTO(Products prodotto) {
        ProductDTO userDTO = new ProductDTO();
        userDTO.setId(prodotto.getId());
        userDTO.setNome(prodotto.getNome());
        userDTO.setCategoria(prodotto.getCategoria());
        userDTO.setDescrizione(prodotto.getDescrizione());
        userDTO.setPrezzo(prodotto.getPrezzo());
        userDTO.setImage(prodotto.getImage());
        return userDTO;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public double getPrezzo() {
        return prezzo;
    }

    public void setPrezzo(double prezzo) {
        this.prezzo = prezzo;
    }

    public String getCategoria() {
        return categoria;
    }

    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public int getQuantita() {
        return quantita;
    }

    public void setQuantita(int quantita) {
        this.quantita = quantita;
    }
}
