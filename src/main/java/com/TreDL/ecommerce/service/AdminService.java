package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.Money;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.OrdersRepository;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class AdminService {
    private final ProductsRepository products;
    private final OrdersRepository orders;

    public AdminService(ProductsRepository products, OrdersRepository orders) {
        this.products = products;
        this.orders = orders;
    }

    public AdminDashboardDTO dashboard() {
        return new AdminDashboardDTO(orders.count(), products.count(), orders.sumConfirmedOrderTotals(), "EUR");
    }

    public List<ProductDTO> products() {
        return products.findAll(Sort.by("id")).stream().map(ProductDTO::convertToDTO).toList();
    }

    @Transactional
    public ProductDTO create(AdminProductRequest request) {
        Products product = new Products();
        apply(product, request);
        return ProductDTO.convertToDTO(products.save(product));
    }

    @Transactional
    public ProductDTO update(long id, AdminProductRequest request) {
        Products product = products.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Prodotto non trovato"));
        apply(product, request);
        return ProductDTO.convertToDTO(product);
    }

    public List<OrderDTO> orders() {
        return orders.findAll(Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id")))
                .stream().map(OrderDTO::convertToDTO).toList();
    }

    public OrderDTO order(long id) {
        return OrderDTO.convertToDTO(orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ordine non trovato")));
    }

    private void apply(Products product, AdminProductRequest request) {
        if (request == null || request.nome() == null || request.nome().isBlank())
            throw new IllegalArgumentException("Il nome del prodotto è obbligatorio");
        validateLength(request.nome());
        validateLength(request.descrizione());
        validateLength(request.categoria());
        validateLength(request.image());
        if (request.prezzo() == null) throw new IllegalArgumentException("Il prezzo è obbligatorio");
        // Validate everything before changing a managed entity.
        var price = Money.validate(request.prezzo());
        Integer stock = null;
        if (request.stock() != null) {
            try { stock = request.stock().intValueExact(); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("La quantità deve essere un intero tra 0 e 2147483647"); }
            if (stock < 0) throw new IllegalArgumentException("La quantità disponibile non può essere negativa");
        }
        product.setNome(request.nome().strip());
        product.setPrezzo(price);
        product.setDescrizione(request.descrizione());
        product.setCategoria(request.categoria());
        if (stock != null) product.setStock(stock);
        // Managed primary images are changed only through the image API.
        if (product.getImage() == null || !product.getImage().startsWith("/media/products/"))
            product.setImage(request.image());
    }

    private void validateLength(String value) {
        if (value != null && value.length() > 255)
            throw new IllegalArgumentException("I campi di testo possono contenere al massimo 255 caratteri");
    }
}
