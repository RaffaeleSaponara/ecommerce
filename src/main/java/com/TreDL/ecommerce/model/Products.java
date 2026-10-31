package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

import java.util.List;
import java.math.BigDecimal;

@Entity
@NoArgsConstructor
@Table(name = "products")
public class Products {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "prezzo", nullable = false, precision = 19, scale = 2)
    private BigDecimal prezzo;
    @Column(name = "nome", nullable = false)
    private String nome;
    @Column(name = "descrizione")
    private String descrizione;
    @Column(name = "categoria")
    private String categoria;
    @Column(name = "image")
    private String image;
    @Column(nullable = false)
    private int stock = 0;

    @ManyToMany(mappedBy = "products")
    private List<Cart> carts;


    @PrePersist @PreUpdate
    private void validatePrice() { prezzo = Money.validate(prezzo); setStock(stock); }

    public int getStock() { return stock; }
    public void setStock(int stock) {
        if (stock < 0) throw new IllegalArgumentException("La quantità disponibile non può essere negativa");
        this.stock = stock;
    }

    public Long getId() {
        return id;
    }


    public BigDecimal getPrezzo() {
        return prezzo;
    }

    public void setPrezzo(BigDecimal prezzo) {
        this.prezzo = Money.validate(prezzo);
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

    public String getCategoria() {
        return categoria;
    }

    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }

    public List<Cart> getCarts() {
        return carts;
    }

    public void setCarts(List<Cart> carts) {
        this.carts = carts;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}
