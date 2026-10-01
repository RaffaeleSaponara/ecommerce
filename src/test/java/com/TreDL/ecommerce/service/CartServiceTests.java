package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CartServiceTests {
    @Autowired
    private CartService cartService;
    @Autowired
    private CustomersService customersService;
    @Autowired
    private ProductsRepository productsRepository;

    @Test
    void removeProductRemovesOnlyOneOccurrence() {
        Customers customer = new Customers();
        customer.setEmail("cart-test@example.com");
        customer.setPassword("password");
        customer.setUsername("cart-test");
        customer.setRole("USER");
        customer = customersService.addCustomer(customer);

        Products product = new Products();
        product.setNome("Test Jig");
        product.setDescrizione("Test product");
        product.setCategoria("Hardbaits");
        product.setPrezzo(10.0);
        product.setImage("/images/b1.jpg");
        product = productsRepository.save(product);

        cartService.addProductToCart(customer, product);
        cartService.addProductToCart(customer, product);

        Cart updatedCart = cartService.removeProductToCart(customer, product);

        assertThat(updatedCart.getProducts()).hasSize(1);
        assertThat(updatedCart.getProducts().get(0).getId()).isEqualTo(product.getId());
    }
}
