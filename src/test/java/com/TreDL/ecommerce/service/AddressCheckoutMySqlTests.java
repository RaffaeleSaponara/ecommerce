package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.CheckoutRequest;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@ContextConfiguration(initializers=MySqlCheckoutTestDatabase.Initializer.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AddressCheckoutMySqlTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CustomersRepository customers;
    @Autowired ProductsRepository products;
    @Autowired CartService carts;
    @Autowired CheckoutService checkout;
    @Autowired JdbcTemplate jdbc;
    @Autowired MySqlCheckoutTestDatabase owned;
    Customers alice, bob;
    @BeforeEach void fixture() {
        for(String table:new String[]{"order_items","orders","cart_products","cart","customer_addresses","product_images","products","customers"})
            jdbc.update("DELETE FROM "+table);
        alice=customer("alice");bob=customer("bob");
        Products p=new Products();p.setNome("Jig");p.setPrezzo(new BigDecimal("3.00"));products.saveAndFlush(p);
        carts.addProductToCart(alice,p);
    }
    Customers customer(String name) {
        Customers c=new Customers();c.setUsername(name);c.setEmail(name+"@example.test");c.setPassword("fixture");c.setRole("USER");return customers.saveAndFlush(c);
    }
    String body(String name,boolean preferred) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("recipientName",name,"street","Via Roma","houseNumber","10/A",
            "postalCode","00100","municipality","Roma","province","RM","country","IT","deliveryNotes","Citofono Rossi","defaultAddress",preferred));
    }
    long address(Customers owner,String name) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/addresses").with(user(owner.getEmail())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(name,false)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
    JsonNode submit(String key,CheckoutRequest request,int expected) throws Exception {
        String response=mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
    @Test void mysqlCheckoutSnapshotsSurviveAddressEditsDeletesAndIdempotentReplay() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(owned.databaseName());
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(owned.expectedServerUuid());
        long shipping=address(alice,"Mario Rossi"),billing=address(alice,"Anna Bianchi"),other=address(alice,"Altro Indirizzo");
        var quote=checkout.quote(alice.getEmail());var request=new CheckoutRequest(quote.quoteToken(),true,shipping,billing);
        JsonNode order=submit("addressed-order",request,200);
        assertThat(order.get("shippingAddress").get("recipientName").asText()).isEqualTo("Mario Rossi");
        assertThat(order.get("billingAddress").get("recipientName").asText()).isEqualTo("Anna Bianchi");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("3.00");
        mvc.perform(put("/api/addresses/"+shipping).with(user(alice.getEmail())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("Modificato",false)))
            .andExpect(status().isOk());
        for(long id:new long[]{shipping,billing})mvc.perform(delete("/api/addresses/"+id).with(user(alice.getEmail())).with(csrf())).andExpect(status().isOk());
        assertThat(submit("addressed-order",request,200)).isEqualTo(order);
        submit("addressed-order",new CheckoutRequest(quote.quoteToken(),true,other,null),409);
        mvc.perform(get("/user").with(user(alice.getEmail()))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Mario Rossi")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Anna Bianchi")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1",Long.class)).isEqualTo(1);
    }
    @Test void ownershipAndMissingAddressCannotCreateOrderOrClearCart() throws Exception {
        long foreign=address(bob,"Bob Rossi");var quote=checkout.quote(alice.getEmail());
        submit("missing",new CheckoutRequest(quote.quoteToken(),true),400);
        submit("foreign",new CheckoutRequest(quote.quoteToken(),true,foreign,null),404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isEqualTo(1);
    }
    @Test void concurrentDefaultChangesRemainSerializedOnCustomerRow() throws Exception {
        long a=address(alice,"Primo Rossi"),b=address(alice,"Secondo Rossi");
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            var one=workers.submit(()->setDefault(a));var two=workers.submit(()->setDefault(b));
            one.get(15,TimeUnit.SECONDS);two.get(15,TimeUnit.SECONDS);
            JsonNode list=mapper.readTree(mvc.perform(get("/api/addresses").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(list.size()).isEqualTo(2);
            assertThat(java.util.stream.StreamSupport.stream(list.spliterator(),false).filter(i->i.get("defaultAddress").asBoolean()).count()).isEqualTo(1);
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    boolean setDefault(long id) throws Exception {
        mvc.perform(put("/api/addresses/"+id+"/default").with(user(alice.getEmail())).with(csrf())).andExpect(status().isOk());return true;
    }
}
