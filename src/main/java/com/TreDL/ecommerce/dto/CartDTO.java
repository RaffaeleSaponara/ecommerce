package com.TreDL.ecommerce.dto;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Products;

import java.util.ArrayList;
import java.util.List;

public class CartDTO {
    private Long id;
    private Long id_customer;
    private List<ProductDTO> items;

    public static CartDTO convertToDTO(Cart cart){
        CartDTO cartDTO = new CartDTO();
        cartDTO.setId(cart.getId());
        cartDTO.setId_customer(cart.getCustomer().getId());
        cartDTO.setItems(cart.getProducts());
        return cartDTO;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId_customer() {
        return id_customer;
    }

    public void setId_customer(Long id_customer) {
        this.id_customer = id_customer;
    }

    public List<ProductDTO> getItems() {
        return items;
    }

    public void setItems(List<Products> items) {
        List<ProductDTO> productsDTO = new ArrayList<>();
        for (Products p: items) {
            productsDTO.add(ProductDTO.convertToDTO(p));
        }
        this.items = productsDTO;
    }
}
