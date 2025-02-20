package com.TreDL.ecommerce.dto;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Products;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<Long, Integer> map = new HashMap<>();

        for (Products p: items) {
            if(map.containsKey(p.getId())){
                map.put(p.getId(), map.get(p.getId())+1);
            }else{
                map.put(p.getId(), 1);
                ProductDTO prodotto = ProductDTO.convertToDTO(p);
                productsDTO.add(prodotto);
            }
        }

        for (ProductDTO dto: productsDTO) {
            dto.setQuantita(map.get(dto.getId()));
        }

        this.items = productsDTO;
    }
}
