package com.TreDL.ecommerce.model;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.DataInitializer;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.hibernate.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutV2ModelTests {
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static Products product() {
        Products p = new Products(); p.setNome("Original"); p.setPrezzo(decimal("12.30")); return p;
    }
    private static Orders order(Customers customer) throws Exception {
        Orders o = new Orders(); o.setCustomer(customer); o.setDate(LocalDateTime.of(2026,10,27,12,0));
        o.setShipment("In elaborazione"); o.setTotal(decimal("36.90"));
        o.setIdempotencyKey("client-key-A".getBytes(StandardCharsets.UTF_8));
        o.setRequestHash(MessageDigest.getInstance("SHA-256").digest("test request payload".getBytes(StandardCharsets.UTF_8)));
        return o;
    }
    @Test void initializerUsesExactPrices() {
        ProductsRepository repository = mock(ProductsRepository.class);
        new DataInitializer(repository).run();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Products>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(saved.capture());
        List<BigDecimal> prices = new ArrayList<>();
        saved.getValue().forEach(p -> prices.add(p.getPrezzo()));
        assertEquals(List.of(decimal("12.90"), decimal("14.50"), decimal("7.90")), prices);
    }
    @Test void exactMoney() {
        assertEquals(decimal("0.30"), Money.validate(decimal("0.10").add(decimal("0.20"))));
        assertEquals(decimal("12.00"), Money.validate(decimal("12")));
        assertEquals(decimal("1.23"), Money.validate(decimal("1.2300")));
        assertEquals(decimal("99999999999999999.99"), Money.validate(decimal("99999999999999999.99")));
    }
    @ParameterizedTest @ValueSource(strings={"-0.01","100000000000000000.00","0.001","12.345"})
    void invalidMoney(String value) {
        assertThrows(IllegalArgumentException.class, () -> Money.validate(decimal(value)));
        assertThrows(IllegalArgumentException.class, () -> new Products().setPrezzo(decimal(value)));
        assertThrows(IllegalArgumentException.class, () -> new Orders().setTotal(decimal(value)));
    }
    @Test void nullMoney() { assertThrows(NullPointerException.class, () -> Money.validate(null)); }
    @Test void snapshotAndSubtotal() throws Exception {
        Products p = product(); Orders o = order(new Customers()); OrderItem i = new OrderItem(o, 2, p, 3);
        p.setNome("Changed"); p.setPrezzo(decimal("99.99"));
        assertEquals("Original", i.getProductName()); assertEquals(decimal("12.30"), i.getUnitPrice());
        assertEquals(decimal("36.90"), i.getSubtotal()); assertEquals(3, i.getQuantity());
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(o,1,p,0));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(o,1,p,-1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(o,0,p,1));
        p.setPrezzo(decimal("99999999999999999.99"));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(o,1,p,2));
    }
    @Test void binaryValidationAndDefensiveCopies() {
        Orders o = new Orders(); byte[] key = {65}; byte[] hash = new byte[32];
        o.setIdempotencyKey(key); o.setRequestHash(hash); key[0]=66; hash[0]=1;
        assertEquals(65,o.getIdempotencyKey()[0]); assertEquals(0,o.getRequestHash()[0]);
        o.getIdempotencyKey()[0]=67; o.getRequestHash()[0]=2;
        assertEquals(65,o.getIdempotencyKey()[0]); assertEquals(0,o.getRequestHash()[0]);
        for (int size : new int[]{0,129}) assertThrows(IllegalArgumentException.class, () -> o.setIdempotencyKey(new byte[size]));
        for (int size : new int[]{0,31,33}) assertThrows(IllegalArgumentException.class, () -> o.setRequestHash(new byte[size]));
        assertThrows(IllegalArgumentException.class, () -> o.setRequestHash(null));
        assertThrows(IllegalArgumentException.class, () -> o.setIdempotencyKey(null));
        o.setIdempotencyKey(new byte[128]);
    }
    @Test void numericJsonAndNoSensitiveFields() throws Exception {
        Products p = product(); Customers c = new Customers(); c.setPassword("secret");
        Orders o = order(c); o.addItem(new OrderItem(o,1,p,3));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = mapper.valueToTree(OrderDTO.convertToDTO(o));
        assertTrue(json.get("total").isNumber());
        assertTrue(mapper.writeValueAsString(OrderDTO.convertToDTO(o)).contains("\"total\":36.90")); assertEquals("EUR",json.get("currency").asText());
        assertTrue(json.at("/items/0/unitPrice").isNumber()); assertTrue(json.at("/items/0/subtotal").isNumber());
        assertEquals("Original",json.at("/items/0/productName").asText());
        for (String field : List.of("password","customer","idempotencyKey","requestHash","product"))
            assertNull(json.findValue(field));
        assertTrue(mapper.valueToTree(ProductDTO.convertToDTO(p)).get("prezzo").isNumber());
        Cart cart = new Cart(); cart.setCustomer(c); cart.setProducts(List.of(p,p,p));
        JsonNode cartJson = mapper.valueToTree(CartDTO.convertToDTO(cart));
        assertTrue(cartJson.at("/items/0/prezzo").isNumber()); assertEquals(3,cartJson.at("/items/0/quantita").asInt());
        assertEquals(1,cartJson.get("items").size()); assertNull(cartJson.findValue("password"));
    }
    @Test void lineOwnershipAndUniqueness() throws Exception {
        Orders o = order(new Customers()); Products p = product();
        o.addItem(new OrderItem(o,2,p,1)); o.addItem(new OrderItem(o,1,p,1));
        assertEquals(List.of(1,2),o.getItems().stream().map(OrderItem::getLineNumber).toList());
        assertThrows(IllegalArgumentException.class, () -> o.addItem(new OrderItem(o,1,p,1)));
        assertThrows(IllegalArgumentException.class, () -> o.addItem(new OrderItem(new Orders(),3,p,1)));
        assertThrows(UnsupportedOperationException.class, () -> o.getItems().clear());
    }
    @Test void jpaValidatesV1AndPreservesHistory() throws Exception {
        String url = "jdbc:h2:mem:model_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        // Holding this connection keeps only this ephemeral in-memory DB alive.
        try (Connection keeper = DriverManager.getConnection(url,"sa", "")) {
            Flyway.configure().dataSource(url,"sa", "").locations("classpath:db/checkout-v2")
                .cleanDisabled(true).baselineOnMigrate(false).load().migrate();
            StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url",url).applySetting("hibernate.connection.username","sa")
                .applySetting("hibernate.connection.password","").applySetting("hibernate.hbm2ddl.auto","validate")
                .build();
            try (SessionFactory factory = new MetadataSources(registry).addAnnotatedClass(Customers.class)
                    .addAnnotatedClass(Products.class).addAnnotatedClass(Cart.class).addAnnotatedClass(Orders.class)
                    .addAnnotatedClass(OrderItem.class).buildMetadata().buildSessionFactory()) {
                Long orderId; Long productId; Long customerId;
                try (Session s = factory.openSession()) {
                    Transaction tx = s.beginTransaction();
                    Customers c = new Customers(); c.setUsername("model"); c.setPassword("secret"); c.setRole("USER"); s.persist(c);
                    Products p = product(); s.persist(p); Orders o = order(c);
                    o.addItem(new OrderItem(o,2,p,1)); o.addItem(new OrderItem(o,1,p,2)); s.persist(o);
                    Cart cart = new Cart(); cart.setCustomer(c); cart.setProducts(new ArrayList<>(List.of(p,p))); s.persist(cart);
                    tx.commit(); orderId=o.getId(); productId=p.getId(); customerId=c.getId();
                }
                try (Session s = factory.openSession()) {
                    Transaction tx = s.beginTransaction(); Products p = s.find(Products.class,productId);
                    p.setNome("Changed"); p.setPrezzo(decimal("99.99")); tx.commit();
                }
                try (Session s = factory.openSession()) {
                    Orders o = s.find(Orders.class,orderId);
                    assertEquals(List.of(1,2),o.getItems().stream().map(OrderItem::getLineNumber).toList());
                    assertEquals("Original",o.getItems().get(0).getProductName());
                    assertEquals(decimal("24.60"),o.getItems().get(0).getSubtotal());
                    assertEquals(decimal("36.90"),o.getTotal()); assertEquals("EUR",o.getCurrency());
                    assertArrayEquals(order(new Customers()).getRequestHash(),o.getRequestHash());
                    assertArrayEquals("client-key-A".getBytes(StandardCharsets.UTF_8),o.getIdempotencyKey());
                    assertEquals(2,s.createQuery("from Cart",Cart.class).getSingleResult().getProducts().size());
                }
                try (Session s = factory.openSession()) {
                    Transaction tx = s.beginTransaction(); Customers c = s.find(Customers.class,customerId);
                    c.setOrders(new ArrayList<>()); s.flush(); tx.commit();
                }
                try (Statement stmt = keeper.createStatement()) {
                    stmt.executeUpdate("DELETE FROM cart_products");
                    stmt.executeUpdate("DELETE FROM cart");
                    stmt.executeUpdate("DELETE FROM products WHERE id=" + productId);
                }
                try (Session s = factory.openSession()) {
                    Orders o = s.find(Orders.class,orderId); assertEquals(2,o.getItems().size());
                    assertNull(o.getItems().get(0).getProduct()); assertEquals("Original",o.getItems().get(0).getProductName());
                    assertEquals(decimal("12.30"),o.getItems().get(0).getUnitPrice());
                    Transaction tx = s.beginTransaction(); s.remove(s.find(Customers.class,customerId));
                    assertThrows(RuntimeException.class,s::flush); tx.rollback();
                }
                try (Session s = factory.openSession()) { assertNotNull(s.find(Orders.class,orderId)); }
            } finally { StandardServiceRegistryBuilder.destroy(registry); }
        }
    }
}
