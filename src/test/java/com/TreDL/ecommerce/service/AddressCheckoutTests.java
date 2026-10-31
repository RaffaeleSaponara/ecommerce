package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test") @Transactional
class AddressCheckoutTests {
    @Autowired CustomersRepository customers;
    @Autowired ProductsRepository products;
    @Autowired OrdersRepository orders;
    @Autowired CheckoutService checkout;
    @Autowired CartService carts;
    @Autowired AddressBookService addresses;
    @Autowired OrderHistoryService history;
    @Autowired EntityManager em;
    Customers customer,other;Products product;Long shipping,billing;
    AddressRequest input(String street) { return new AddressRequest("Mario Rossi",street,"10","00100","Roma","RM","IT","Citofono Rossi",false); }
    Customers customer() {
        Customers c=new Customers();String name=java.util.UUID.randomUUID().toString();c.setUsername(name);c.setEmail(name+"@example.com");c.setPassword("unused");c.setRole("USER");return customers.saveAndFlush(c);
    }
    @BeforeEach void setup() {
        customer=customer();other=customer();product=new Products();product.setNome("Address product");product.setPrezzo(new BigDecimal("12.50"));products.saveAndFlush(product);
        carts.addProductToCart(customer,product);
        shipping=addresses.create(customer.getEmail(),input("Via Roma")).id();billing=addresses.create(customer.getEmail(),input("Via Milano")).id();
    }
    CheckoutRequest request(Long shipping,Long billing) { return new CheckoutRequest(checkout.quote(customer.getEmail()).quoteToken(),true,shipping,billing); }
    void rejected(Runnable action,int status) { assertThatThrownBy(action::run).isInstanceOfSatisfying(CheckoutException.class,e->assertThat(e.getStatus().value()).isEqualTo(status)); }
    @Test void snapshotsSurviveEditsDeletionAndReplay() {
        CheckoutRequest req=request(shipping,billing);OrderDTO result=checkout.checkout(customer.getEmail(),"snapshot",req);
        assertThat(result.shippingAddress().getStreet()).isEqualTo("Via Roma");assertThat(result.billingAddress().getStreet()).isEqualTo("Via Milano");
        addresses.update(customer.getEmail(),shipping,input("Via Nuova"));addresses.delete(customer.getEmail(),shipping);addresses.delete(customer.getEmail(),billing);
        em.flush();em.clear();
        OrderDTO replay=checkout.checkout(customer.getEmail(),"snapshot",req);
        assertThat(replay).isEqualTo(result);
        assertThat(history.forCustomer(customer.getEmail()).get(0).shippingAddress()).isEqualTo(result.shippingAddress());
        assertThat(history.forCustomer(other.getEmail())).isEmpty();
    }
    @Test void shippingAndBillingMustBelongToPrincipalAndMissingShippingCreatesNothing() {
        Long foreign=addresses.create(other.getEmail(),input("Via Estera")).id();
        rejected(()->checkout.checkout(customer.getEmail(),"no-shipping",request(null,null)),400);
        rejected(()->checkout.checkout(customer.getEmail(),"foreign-shipping",request(foreign,null)),404);
        rejected(()->checkout.checkout(customer.getEmail(),"foreign-billing",request(shipping,foreign)),404);
        rejected(()->checkout.checkout(customer.getEmail(),"missing",request(Long.MAX_VALUE,null)),404);
        assertThat(orders.findByCustomerEmailOrderByDateDescIdDesc(customer.getEmail())).isEmpty();
    }
    @Test void effectiveBillingAndLegacyHashAreCanonicalAndIdChangesConflict() {
        CheckoutRequest req=request(shipping,null);
        assertThat(CheckoutService.requestHash(req)).isEqualTo(CheckoutService.requestHash(new CheckoutRequest(req.quoteToken(),true,shipping,shipping)));
        assertThat(CheckoutService.requestHash(new CheckoutRequest(req.quoteToken(),true))).isEqualTo(QuoteSigner.requestHash(req.quoteToken()));
        rejected(()->CheckoutService.requestHash(new CheckoutRequest(req.quoteToken(),true,0L,null)),400);
        OrderDTO result=checkout.checkout(customer.getEmail(),"same",req);
        assertThat(result.billingAddress()).isEqualTo(result.shippingAddress());
        assertThat(checkout.checkout(customer.getEmail(),"same",new CheckoutRequest(req.quoteToken(),true,shipping,shipping))).isEqualTo(result);
        rejected(()->checkout.checkout(customer.getEmail(),"same",new CheckoutRequest(req.quoteToken(),true,billing,null)),409);
    }
    @Test void legacyReplayDoesNotRequireAnAddressOrValidQuote() {
        Orders order=new Orders();order.setCustomer(customer);order.setDate(java.time.LocalDateTime.now());order.setTotal(new BigDecimal("0.00"));order.setShipment("Legacy");
        order.setIdempotencyKey(CheckoutService.keyBytes("legacy"));order.setRequestHash(QuoteSigner.requestHash("old-expired-token"));orders.saveAndFlush(order);
        OrderDTO replay=checkout.checkout(customer.getEmail(),"legacy",new CheckoutRequest("old-expired-token",true));
        assertThat(replay.id()).isEqualTo(order.getId());assertThat(replay.shippingAddress()).isNull();assertThat(replay.billingAddress()).isNull();
    }
}
