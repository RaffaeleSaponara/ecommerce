package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.PaymentAttemptsRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"payments.stripe.enabled=true","payments.stripe.secret-key=sk_test_fixture_only"})
@AutoConfigureMockMvc
class StripeDomainTests extends StockCheckoutContract {
    @Autowired StripePaymentDomain domain;
    @Autowired PaymentAttemptsRepository payments;
    @Autowired OrderManagementService management;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockitoBean Clock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OrderMailEvents mail;

    @BeforeEach void controlledClock() {
        when(clock.instant()).thenReturn(Instant.parse("2030-01-01T12:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        clearInvocations(mail);
    }
    @AfterEach void removePaymentFixtures() {
        for(String table:List.of("refunds","payment_events","payment_attempts"))jdbc.update("DELETE FROM "+table);
    }
    OrderDTO reserve() {
        carts.addProductToCart(alice,product);
        return domain.reserve(alice.getEmail(),"stripe",request(alice));
    }
    PaymentAttempt attempt(long orderId) {return payments.findByOrderId(orderId).orElseThrow();}
    void rejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test void reservationConfirmationAndReplayDeductOnlyOnceAndSendNoPrematureEmail() {
        stock(1);carts.addProductToCart(alice,product);var signed=request(alice);
        var order=domain.reserve(alice.getEmail(),"stripe",signed);
        assertThat(order.shipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
        assertThat(available()).isZero();assertThat(payments.count()).isEqualTo(1);
        verify(mail,never()).confirmed(any());
        assertThat(orders.sumConfirmedOrderTotals()).isEqualByComparingTo("0");
        assertThat(domain.reserve(alice.getEmail(),"stripe",signed)).isEqualTo(order);
        assertThat(payments.count()).isEqualTo(1);
        var attempt=attempt(order.id());
        assertThat(attempt.getExpiresAt()).isEqualTo(LocalDateTime.parse("2030-01-01T12:30:00"));
        var paid=domain.confirm(attempt.getId(),order.total(),"EUR");
        assertThat(paid.shipment()).isEqualTo("CONFERMATO");
        assertThat(domain.confirm(attempt.getId(),order.total(),"EUR")).isEqualTo(paid);
        rejected(()->domain.closeUnpaid(attempt.getId(),PaymentStatus.CANCELLED));
        assertThat(domain.reserve(alice.getEmail(),"stripe",signed)).isEqualTo(paid);
        assertThat(available()).isZero();
        assertThat(orders.sumConfirmedOrderTotals()).isEqualByComparingTo(order.total());
        verify(mail,times(1)).confirmed(any());
        assertThat(management.update(order.id(),"IN_PREPARAZIONE").shipment()).isEqualTo("IN_PREPARAZIONE");
        assertThat(management.update(order.id(),"SPEDITO").shipment()).isEqualTo("SPEDITO");
    }

    @Test void localExpirationAndClosureRequestNeverReleaseStock() {
        var order=reserve();var attempt=attempt(order.id());
        when(clock.instant()).thenReturn(Instant.parse("2030-01-02T12:00:00Z"));
        domain.requestClosure(attempt.getId());domain.requestClosure(attempt.getId());
        assertThat(payments.findById(attempt.getId()).orElseThrow().getCloseRequestedAt()).isNotNull();
        assertThat(attempt(order.id()).getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(orders.findById(order.id()).orElseThrow().getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
        assertThat(available()).isEqualTo(4);
    }

    @Test void definitiveCancellationRestoresOnceAndPreventsSubsequentConfirmation() {
        var order=reserve();var attempt=attempt(order.id());
        var cancelled=domain.closeUnpaid(attempt.getId(),PaymentStatus.CANCELLED);
        assertThat(cancelled.shipment()).isEqualTo("ANNULLATO");
        assertThat(domain.closeUnpaid(attempt.getId(),PaymentStatus.CANCELLED)).isEqualTo(cancelled);
        assertThat(available()).isEqualTo(5);
        assertThat(orders.findById(order.id()).orElseThrow().getStockDeducted()).isFalse();
        rejected(()->domain.confirm(attempt.getId(),order.total(),"EUR"));
        assertThat(available()).isEqualTo(5);
        verify(mail,times(1)).transitioned(any());
    }

    @Test void allNegativeTerminalOutcomesAndInvalidAmountsAreGuarded() {
        var order=reserve();var attempt=attempt(order.id());
        rejected(()->domain.confirm(attempt.getId(),new BigDecimal("0.01"),"EUR"));
        rejected(()->domain.confirm(attempt.getId(),order.total(),"USD"));
        assertThatThrownBy(()->domain.closeUnpaid(attempt.getId(),PaymentStatus.SUCCEEDED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(attempt(order.id()).getStatus()).isEqualTo(PaymentStatus.CREATED);
        domain.closeUnpaid(attempt.getId(),PaymentStatus.EXPIRED);
        rejected(()->domain.closeUnpaid(attempt.getId(),PaymentStatus.FAILED));
        assertThat(available()).isEqualTo(5);
    }

    @Test void stripeCannotBeConfirmedOrReleasedThroughAdminEvenWithCsrf() throws Exception {
        var order=reserve();
        for(String next:List.of("CONFERMATO","IN_PREPARAZIONE","SPEDITO","CONSEGNATO","ANNULLATO")) {
            mvc.perform(post("/api/admin/orders/"+order.id()+"/status")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content("{\"status\":\""+next+"\"}")).andExpect(status().isConflict());
        }
        // Even an inconsistent order state must not authorize unpaid fulfillment.
        jdbc.update("UPDATE orders SET ship_status='CONFERMATO' WHERE id=?",order.id());
        rejected(()->management.update(order.id(),"IN_PREPARAZIONE"));
        assertThat(available()).isEqualTo(4);
        assertThat(Modifier.isPublic(StripePaymentDomain.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(StripePaymentDomain.class.getDeclaredMethods()).filter(m->m.getName().equals("confirm")))
                .allSatisfy(m->assertThat(Modifier.isPublic(m.getModifiers())).isFalse());
        assertThat(mappings.getHandlerMethods().values()).noneMatch(handler->StripePaymentDomain.class.isAssignableFrom(handler.getBeanType()));
    }

    @Test void legacyCheckoutAndHistoricalOrdersNeedNoPaymentAttempt() {
        carts.addProductToCart(alice,product);var signed=request(alice);
        var legacy=checkout.checkout(alice.getEmail(),"legacy",signed);
        assertThat(orders.findById(legacy.id()).orElseThrow().getPaymentFlow()).isEqualTo(PaymentFlow.LEGACY);
        assertThat(payments.count()).isZero();
        assertThat(checkout.checkout(alice.getEmail(),"legacy",signed)).isEqualTo(legacy);
        conflict(()->domain.reserve(alice.getEmail(),"legacy",signed));
        management.update(legacy.id(),"IN_PREPARAZIONE");
        management.update(legacy.id(),"ANNULLATO");management.update(legacy.id(),"ANNULLATO");
        assertThat(available()).isEqualTo(5);
    }

    @Test void reservationAndConfirmationAndClosureRollbackAsOneUnit() {
        carts.addProductToCart(alice,product);var signed=request(alice);
        var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{domain.reserve(alice.getEmail(),"rollback-reserve",signed);status.setRollbackOnly();});
        assertThat(payments.count()).isZero();assertThat(orders.count()).isZero();assertThat(available()).isEqualTo(5);
        var order=domain.reserve(alice.getEmail(),"rollback-reserve",signed);var attempt=attempt(order.id());
        tx.executeWithoutResult(status->{domain.confirm(attempt.getId(),order.total(),"EUR");status.setRollbackOnly();});
        assertThat(attempt(order.id()).getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(orders.findById(order.id()).orElseThrow().getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
        assertThat(available()).isEqualTo(4);
        tx.executeWithoutResult(status->{domain.closeUnpaid(attempt.getId(),PaymentStatus.FAILED);status.setRollbackOnly();});
        assertThat(attempt(order.id()).getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(available()).isEqualTo(4);
    }

    @Test void twoReservationsForLastUnitCreateExactlyOneAttempt() throws Exception {
        stock(1);carts.addProductToCart(alice,product);carts.addProductToCart(bob,product);
        var first=request(alice);var second=request(bob);
        race(()->domain.reserve(alice.getEmail(),"one",first),()->domain.reserve(bob.getEmail(),"two",second),false);
        assertThat(available()).isZero();assertThat(orders.count()).isEqualTo(1);assertThat(payments.count()).isEqualTo(1);
    }

    @Test void concurrentConfirmationAndDefinitiveClosureHaveExactlyOneWinner() throws Exception {
        var order=reserve();var attempt=attempt(order.id());
        race(()->domain.confirm(attempt.getId(),order.total(),"EUR"),()->domain.closeUnpaid(attempt.getId(),PaymentStatus.CANCELLED),true);
        var status=attempt(order.id()).getStatus();
        var saved=orders.findById(order.id()).orElseThrow();
        if(status==PaymentStatus.SUCCEEDED) {
            assertThat(saved.getShipment()).isEqualTo("CONFERMATO");assertThat(available()).isEqualTo(4);
            assertThat(saved.getStockDeducted()).isTrue();
        } else {
            assertThat(status).isEqualTo(PaymentStatus.CANCELLED);assertThat(saved.getShipment()).isEqualTo("ANNULLATO");
            assertThat(available()).isEqualTo(5);assertThat(saved.getStockDeducted()).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes WHERE id_order=?",Long.class,order.id())).isEqualTo(1);
    }

    @Test void concurrentConfirmationAndAdministrativeCancellationCannotRestoreStock() throws Exception {
        var order=reserve();var attempt=attempt(order.id());
        race(()->domain.confirm(attempt.getId(),order.total(),"EUR"),()->management.update(order.id(),"ANNULLATO"),true);
        assertThat(attempt(order.id()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(available()).isEqualTo(4);
        assertThat(orders.findById(order.id()).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
    }

    @Test void twoConcurrentCancellationsRestoreOnlyOnce() throws Exception {
        var order=reserve();var attempt=attempt(order.id());
        race(()->domain.closeUnpaid(attempt.getId(),PaymentStatus.CANCELLED),()->domain.closeUnpaid(attempt.getId(),PaymentStatus.CANCELLED),true,2);
        assertThat(available()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes WHERE id_order=?",Long.class,order.id())).isEqualTo(1);
    }

    @Test void twoConcurrentConfirmationsDoNotConsumeAgainOrDuplicateHistoryAndMail() throws Exception {
        var order=reserve();var attempt=attempt(order.id());
        race(()->domain.confirm(attempt.getId(),order.total(),"EUR"),
                ()->domain.confirm(attempt.getId(),order.total(),"EUR"),true,2);
        assertThat(available()).isEqualTo(4);
        assertThat(attempt(order.id()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(orders.findById(order.id()).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes WHERE id_order=?",Long.class,order.id())).isEqualTo(1);
        verify(mail,times(1)).confirmed(any());
    }

    @Test void concurrentReplaysOfOneReservationCreateOneOrderAndOneAttempt() throws Exception {
        carts.addProductToCart(alice,product);var signed=request(alice);
        race(()->domain.reserve(alice.getEmail(),"same-reservation",signed),
                ()->domain.reserve(alice.getEmail(),"same-reservation",signed),true,2);
        assertThat(orders.count()).isEqualTo(1);assertThat(payments.count()).isEqualTo(1);
        assertThat(available()).isEqualTo(4);
        verify(mail,never()).confirmed(any());
    }

    void race(Runnable one,Runnable two,boolean domainErrors) throws Exception {race(one,two,domainErrors,1);}
    void race(Runnable one,Runnable two,boolean domainErrors,int successes) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var a=workers.submit(()->runRace(one,ready,start,domainErrors));
            var b=workers.submit(()->runRace(two,ready,start,domainErrors));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)).stream().filter(v->v==200).count()).isEqualTo(successes);
        } finally {start.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    int runRace(Runnable action,CountDownLatch ready,CountDownLatch start,boolean domainErrors) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Race timeout");
        try {action.run();return 200;}
        catch(ResponseStatusException e){if(domainErrors && e.getStatusCode().value()==409)return 409;throw e;}
        catch(CheckoutException e){if(!domainErrors && e.getStatus().value()==409)return 409;throw e;}
    }
}
