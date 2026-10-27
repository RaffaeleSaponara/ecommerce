package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CartService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CartServiceTests {
    @Autowired private CartService cartService;
    @Autowired private CartRepository carts;
    @Autowired private CustomersRepository customers;
    @Autowired private ProductsRepository products;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private Customers customer;
    private Products product;
    private Products otherProduct;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        customer = newCustomer("cart-test");
        product = newProduct("Test Jig");
        otherProduct = newProduct("Test Lure");
    }

    @AfterEach
    void cleanCommittedFixtures() {
        transactions.executeWithoutResult(status -> {
            carts.deleteAll();
            customers.deleteAll();
            products.deleteAll();
        });
    }

    @Test
    void createsOneEmptyCartAndReusesIt() {
        Long cartId = cartService.getCart(customer).getId();

        assertThat(cartService.getCart(customer).getId()).isEqualTo(cartId);
        assertThat(persistedProductIds(customer)).isEmpty();
        assertThat(carts.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void persistsEveryAddedOccurrence(int quantity) {
        addCopies(customer, product, quantity);

        assertThat(persistedProductIds(customer)).hasSize(quantity).containsOnly(product.getId());
    }

    @Test
    void persistsDifferentProductsInTheSameCart() {
        cartService.addProductToCart(customer, product);
        cartService.addProductToCart(customer, otherProduct);

        assertThat(persistedProductIds(customer))
                .containsExactlyInAnyOrder(product.getId(), otherProduct.getId());
    }

    @Test
    void removesTheLastOccurrenceWithoutDeletingProductOrCart() {
        Long cartId = cartService.addProductToCart(customer, product).getId();

        cartService.removeProductToCart(customer, product);

        assertThat(persistedProductIds(customer)).isEmpty();
        assertThat(carts.existsById(cartId)).isTrue();
        assertThat(products.existsById(product.getId())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5})
    void removesOnlyOneOccurrenceAndPreservesOtherProducts(int quantity) {
        addCopies(customer, product, quantity);
        cartService.addProductToCart(customer, otherProduct);

        // A separately loaded argument must be matched by persisted ID.
        Products detachedProduct = products.findById(product.getId()).orElseThrow();
        cartService.removeProductToCart(customer, detachedProduct);

        List<Long> ids = persistedProductIds(customer);
        assertThat(ids).hasSize(quantity);
        assertThat(ids.stream().filter(product.getId()::equals).count()).isEqualTo(quantity - 1L);
        assertThat(ids).containsOnlyOnce(otherProduct.getId());
    }

    @Test
    void removingAnAbsentProductPreservesExistingContents() {
        cartService.addProductToCart(customer, product);

        cartService.removeProductToCart(customer, otherProduct);

        assertThat(persistedProductIds(customer)).containsExactly(product.getId());
    }

    @Test
    void removingFromAnExistingEmptyCartIsHarmless() {
        cartService.getCart(customer);

        cartService.removeProductToCart(customer, product);

        assertThat(persistedProductIds(customer)).isEmpty();
    }

    @Test
    void clearsAllOccurrencesAndCanBeRepeated() {
        addCopies(customer, product, 3);
        Long cartId = cartService.addProductToCart(customer, otherProduct).getId();

        cartService.clearCart(customer.getId());

        assertThat(persistedProductIds(customer)).isEmpty();
        assertThat(carts.existsById(cartId)).isTrue();
        assertThat(products.count()).isEqualTo(2);
        cartService.clearCart(customer.getId());
        assertThat(persistedProductIds(customer)).isEmpty();
    }

    @Test
    void clearingACustomerWithoutCartDoesNotCreateOne() {
        assertThatCode(() -> cartService.clearCart(customer.getId())).doesNotThrowAnyException();

        assertThat(carts.findByCustomerId(customer.getId())).isEmpty();
    }

    @Test
    void addingAfterClearStartsWithOnlyTheNewProduct() {
        addCopies(customer, product, 2);
        cartService.clearCart(customer.getId());

        cartService.addProductToCart(customer, otherProduct);

        assertThat(persistedProductIds(customer)).containsExactly(otherProduct.getId());
    }

    @Test
    void mutationsDoNotAffectAnotherCustomersCart() {
        Customers otherCustomer = newCustomer("other-customer");
        addCopies(otherCustomer, product, 2);
        addCopies(customer, product, 2);

        cartService.removeProductToCart(customer, product);
        assertThat(persistedProductIds(otherCustomer)).containsExactly(product.getId(), product.getId());
        cartService.clearCart(customer.getId());

        assertThat(persistedProductIds(customer)).isEmpty();
        assertThat(persistedProductIds(otherCustomer)).containsExactly(product.getId(), product.getId());
    }

    private void addCopies(Customers owner, Products item, int quantity) {
        for (int i = 0; i < quantity; i++) {
            cartService.addProductToCart(owner, item);
        }
    }

    private List<Long> persistedProductIds(Customers owner) {
        // Service calls have already committed. Read in a new persistence context
        // and materialize the lazy collection before the transaction closes.
        return transactions.execute(status -> carts.findByCustomerId(owner.getId()).orElseThrow()
                .getProducts().stream().map(Products::getId).toList());
    }

    private Customers newCustomer(String username) {
        Customers result = new Customers();
        result.setUsername(username);
        result.setEmail(username + "@example.com");
        result.setPassword("unused-test-password");
        result.setRole("USER");
        return customers.saveAndFlush(result);
    }

    private Products newProduct(String name) {
        Products result = new Products();
        result.setNome(name);
        result.setDescrizione("Test product");
        result.setCategoria("Hardbaits");
        result.setPrezzo(10.0);
        result.setImage("/images/test.jpg");
        return products.saveAndFlush(result);
    }
}
