package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import com.TreDL.ecommerce.model.PaymentStatus;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StripeRefundTests extends StripeDomainTests {
    @Autowired StripeRefundOperations refunds;
    @Autowired StripeRefundReconciliation refundWorker;
    @MockitoBean StripeSessionGateway sessions;
    @MockitoBean StripeRefundGateway refundGateway;
    long orderId;String attemptId;
    Instant instant;
    final AtomicInteger creations=new AtomicInteger();
    final Map<String,StripeRefundGateway.RemoteRefund> provider=new ConcurrentHashMap<>();
    String providerStatus;
    @BeforeEach void refundAdapter(){
        instant=Instant.parse("2030-01-01T12:00:00Z");when(clock.instant()).thenAnswer(c->instant);
        provider.clear();creations.set(0);providerStatus="pending";
        when(refundGateway.capture(anyString())).thenAnswer(c->{outside();return new StripeRefundGateway.Capture("pi_refund","ch_refund",1840,"eur",false,true);});
        when(refundGateway.list(anyString())).thenAnswer(c->{outside();return List.copyOf(provider.values());});
        when(refundGateway.create(anyMap(),anyString())).thenAnswer(c->{outside();
            synchronized(provider){
                String key=c.getArgument(1);assertThat(key).startsWith("3dl-refund-");
                if(provider.isEmpty()) {creations.incrementAndGet();var params=c.getArgument(0,Map.class);
                    provider.put("re_fixture",new StripeRefundGateway.RemoteRefund("re_fixture","pi_refund","ch_refund",1840,"eur",providerStatus,(Map<String,String>)params.get("metadata")));}
                return provider.values().iterator().next();
            }
        });
        when(refundGateway.retrieve(anyString())).thenAnswer(c->{outside();var r=provider.get(c.getArgument(0,String.class));
            return new StripeRefundGateway.RemoteRefund(r.id(),r.payment(),r.charge(),r.amount(),r.currency(),providerStatus,r.metadata());});
    }
    void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    void fixture(boolean paid){
        var o=reserve();orderId=o.id();attemptId=attempt(orderId).getId();
        jdbc.update("UPDATE payment_attempts SET provider_session_id='cs_test_refund',provider_payment_id='pi_refund' WHERE id=?",attemptId);
        when(sessions.retrieve("cs_test_refund")).thenAnswer(c->{outside();return new StripeSessionGateway.Session("cs_test_refund","pi_refund",1840,"eur",false,"complete","paid",null,1893501060L,Map.of("order_id",""+orderId,"attempt_id",attemptId));});
        if(paid)domain.confirm(attemptId,o.total(),"EUR");
    }
    void advance(long minutes){instant=instant.plusSeconds(minutes*60);}
    String refundStatus(){return jdbc.queryForObject("SELECT status FROM refunds WHERE attempt_id=?",String.class,attemptId);}
    String issue(){return jdbc.queryForObject("SELECT issue FROM refunds WHERE attempt_id=?",String.class,attemptId);}
    @Test void inconsistentRefundSnapshotIsBlockedBeforeAnyProviderRequest(){
        fixture(true);refunds.request(orderId,true,true);
        jdbc.update("UPDATE refunds SET amount=1 WHERE attempt_id=?",attemptId);clearInvocations(refundGateway,sessions);
        refundWorker.tick();assertThat(issue()).isEqualTo("REFUND_SNAPSHOT_MISMATCH");verifyNoInteractions(refundGateway,sessions);
        jdbc.update("UPDATE refunds SET amount=18.40 WHERE attempt_id=?",attemptId);advance(6);refundWorker.tick();
        assertThat(creations).hasValue(0);verify(refundGateway,never()).create(anyMap(),anyString());
    }
    @Test void verifiedRefundDoesNotHideProcessedAnomalyWithConflictingOrderHint(){
        fixture(true);refunds.request(orderId,true,true);
        jdbc.update("INSERT INTO payment_events(id,attempt_id,event_type,received_at,processed_at,outcome,session_ref,attempt_hint,order_hint,retries) VALUES('evt_conflicting',?,'checkout.session.completed',?,?,'ANOMALY','cs_test_refund',?,?,0)",
            attemptId,java.sql.Timestamp.valueOf("2030-01-01 12:00:00"),java.sql.Timestamp.valueOf("2030-01-01 12:00:00"),attemptId,orderId+1000);
        refundWorker.tick();providerStatus="succeeded";advance(2);refundWorker.tick();
        assertThat(refundStatus()).isEqualTo("SUCCEEDED");assertThat(refunds.anomalies()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM payment_events WHERE id='evt_conflicting'",java.sql.Timestamp.class)).isNull();
    }
    @Test void refundForAnotherPaymentCannotBeConfirmed(){
        fixture(true);refunds.request(orderId,true,true);refundWorker.tick();
        doReturn(new StripeRefundGateway.RemoteRefund("re_fixture","pi_other","ch_refund",1840,"eur","succeeded",Map.of()))
            .when(refundGateway).retrieve("re_fixture");
        advance(2);refundWorker.tick();assertThat(refundStatus()).isEqualTo("PENDING");assertThat(issue()).isEqualTo("REFUND_MISMATCH");
        assertThat(creations).hasValue(1);assertThat(available()).isEqualTo(5);
    }
    @Test void frozenParametersAndStableKeySurviveRestartAfterTimeout() throws Exception {
        fixture(true);refunds.request(orderId,true,true);
        doThrow(new StripeRefundGateway.Failure(false)).when(refundGateway).create(anyMap(),anyString());refundWorker.tick();
        var row=jdbc.queryForMap("SELECT idempotency_key,request_json,first_sent_at FROM refunds WHERE attempt_id=?",attemptId);
        String frozen=jdbc.queryForObject("SELECT request_json FROM refunds WHERE attempt_id=?",String.class,attemptId);
        var params=new com.fasterxml.jackson.databind.ObjectMapper().readTree(frozen).get("params");
        doAnswer(call->{assertThat(call.getArgument(1,String.class)).isEqualTo(row.get("idempotency_key"));
            com.fasterxml.jackson.databind.JsonNode sent=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(call.getArgument(0,Map.class));
            assertThat(sent).isEqualTo(params);
            return new StripeRefundGateway.RemoteRefund("re_restart","pi_refund","ch_refund",1840,"eur","pending",Map.of());})
            .when(refundGateway).create(anyMap(),anyString());
        advance(6);var restarted=new StripeRefundReconciliation(refunds,refundGateway,jdbc,transactions,"sk_test_fixture_only");restarted.tick();
        assertThat(refundStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT request_json FROM refunds WHERE attempt_id=?",String.class,attemptId)).isEqualTo(frozen);
        assertThat(jdbc.queryForMap("SELECT first_sent_at FROM refunds WHERE attempt_id=?",attemptId).get("first_sent_at")).isEqualTo(row.get("first_sent_at"));
    }
    @Test void concurrentPaymentConfirmationAndRefundCannotDoubleDeductOrRestore() throws Exception {
        fixture(false);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();domain.confirm(attemptId,new java.math.BigDecimal("18.40"),"EUR");return true;});
            var b=pool.submit(()->{start.await();try{refunds.request(orderId,true,true);}catch(ResponseStatusException expected){assertThat(expected.getStatusCode().value()).isEqualTo(409);}return true;});
            start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();}
        String saved=orders.findById(orderId).orElseThrow().getShipment();assertThat(saved).isIn("CONFERMATO","ANNULLATO");
        assertThat(attempt(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(available()).isEqualTo("ANNULLATO".equals(saved)?5:4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds",Integer.class)).isEqualTo("ANNULLATO".equals(saved)?1:0);
    }
    @Test void fullRefundRequiresLaterReadAndNeverRestocks(){
        fixture(true);var intent=refunds.request(orderId,true,true);
        assertThat(intent.refundStatus()).isEqualTo("REQUESTED");assertThat(intent.amount()).isEqualByComparingTo("18.40");
        assertThat(available()).isEqualTo(5);assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("ANNULLATO");
        providerStatus="succeeded";refundWorker.tick();assertThat(refundStatus()).isEqualTo("PENDING");
        advance(2);refundWorker.tick();assertThat(refundStatus()).isEqualTo("SUCCEEDED");
        refundWorker.tick();refunds.request(orderId,true,true);assertThat(creations).hasValue(1);assertThat(available()).isEqualTo(5);
    }
    @Test void duplicateAndConcurrentCommandsAndWorkersCreateOneRefund() throws Exception {
        fixture(true);var pool=Executors.newFixedThreadPool(2);var barrier=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{barrier.await();return refunds.request(orderId,true,true);});
            var b=pool.submit(()->{barrier.await();return refunds.request(orderId,true,true);});barrier.countDown();
            a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
            var c=pool.submit(refundWorker::tick);var d=pool.submit(refundWorker::tick);c.get(20,TimeUnit.SECONDS);d.get(20,TimeUnit.SECONDS);
        }finally{barrier.countDown();pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds",Integer.class)).isEqualTo(1);
        assertThat(creations).hasValue(1);assertThat(available()).isEqualTo(5);
    }
    @Test void timeoutAfterRemoteCreationFindsExistingRefundInsteadOfCreatingAnother(){
        fixture(true);refunds.request(orderId,true,true);
        doAnswer(c->{outside();creations.incrementAndGet();provider.put("re_lost",new StripeRefundGateway.RemoteRefund("re_lost","pi_refund","ch_refund",1840,"eur","pending",Map.of()));throw new StripeRefundGateway.Failure(false);})
            .when(refundGateway).create(anyMap(),anyString());
        refundWorker.tick();assertThat(refundStatus()).isEqualTo("REQUESTED");advance(6);refundWorker.tick();
        assertThat(refundStatus()).isEqualTo("PENDING");assertThat(creations).hasValue(1);assertThat(available()).isEqualTo(5);
    }
    @Test void actualLocalSaveFailureAfterProviderCreationIsRecoverable(){
        fixture(true);refunds.request(orderId,true,true);
        jdbc.execute("ALTER TABLE refunds ADD CONSTRAINT refund_save_failure CHECK (provider_refund_id IS NULL)");
        try{refundWorker.tick();assertThat(refundStatus()).isEqualTo("REQUESTED");assertThat(creations).hasValue(1);}
        finally{jdbc.execute("ALTER TABLE refunds DROP "+("MySQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName()))?"CHECK":"CONSTRAINT")+" refund_save_failure");}
        advance(6);refundWorker.tick();assertThat(refundStatus()).isEqualTo("PENDING");assertThat(creations).hasValue(1);
    }
    @Test void conservativeWindowAndDefinitiveErrorsNeverCreateAgain(){
        fixture(true);refunds.request(orderId,true,true);
        doThrow(new StripeRefundGateway.Failure(false)).when(refundGateway).create(anyMap(),anyString());
        refundWorker.tick();clearInvocations(refundGateway);advance(13*60);refundWorker.tick();
        assertThat(issue()).isEqualTo("REVIEW_REQUIRED");verify(refundGateway,never()).create(anyMap(),anyString());
        advance(61);refundWorker.tick();verify(refundGateway,never()).create(anyMap(),anyString());
    }
    @Test void definitiveRejectionRemainsReadOnlyEvenAfterAnotherProviderError(){
        fixture(true);refunds.request(orderId,true,true);doThrow(new StripeRefundGateway.Failure(true)).when(refundGateway).create(anyMap(),anyString());
        refundWorker.tick();advance(61);doThrow(new StripeRefundGateway.Failure(false)).when(refundGateway).capture(anyString());refundWorker.tick();
        advance(6);doReturn(new StripeRefundGateway.Capture("pi_refund","ch_refund",1840,"eur",false,true)).when(refundGateway).capture(anyString());
        clearInvocations(refundGateway);refundWorker.tick();verify(refundGateway,never()).create(anyMap(),anyString());
    }
    @Test void crashLeaseCanBeRecoveredByNewWorkerInstance(){
        fixture(true);refunds.request(orderId,true,true);
        jdbc.update("UPDATE refunds SET lease_token='crashed',lease_until=?",java.sql.Timestamp.valueOf("2030-01-01 12:01:00"));
        refundWorker.tick();assertThat(creations).hasValue(0);advance(2);
        var restarted=new StripeRefundReconciliation(refunds,refundGateway,jdbc,transactions,"sk_test_fixture_only");
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"em",org.springframework.test.util.ReflectionTestUtils.getField(refundWorker,"em"));
        restarted.tick();assertThat(creations).hasValue(1);assertThat(refundStatus()).isEqualTo("PENDING");
    }
    @Test void partialExternalRefundBlocksAnyNewCreation(){
        fixture(true);refunds.request(orderId,true,true);
        provider.put("re_external",new StripeRefundGateway.RemoteRefund("re_external","pi_refund","ch_refund",100,"eur","pending",Map.of()));
        refundWorker.tick();assertThat(issue()).isEqualTo("EXISTING_REFUND_CONFLICT");assertThat(creations).hasValue(0);
        advance(6);refundWorker.tick();assertThat(creations).hasValue(0);assertThat(available()).isEqualTo(5);
    }
    @Test void existingExternalFullRefundIsAdoptedWithoutCreatingAnother(){
        fixture(true);refunds.request(orderId,true,true);
        provider.put("re_external",new StripeRefundGateway.RemoteRefund("re_external","pi_refund","ch_refund",1840,"eur","succeeded",Map.of()));
        advance(6);refundWorker.tick();providerStatus="succeeded";advance(2);refundWorker.tick();
        assertThat(refundStatus()).isEqualTo("SUCCEEDED");assertThat(creations).hasValue(0);assertThat(available()).isEqualTo(5);
    }
    @Test void failedRefundLeavesOrderCancelledAndStockUnchanged(){
        fixture(true);refunds.request(orderId,true,true);refundWorker.tick();providerStatus="failed";advance(2);refundWorker.tick();
        assertThat(refundStatus()).isEqualTo("FAILED");assertThat(issue()).isEqualTo("REFUND_FAILED");
        advance(100);refundWorker.tick();refunds.request(orderId,true,true);
        assertThat(creations).hasValue(1);assertThat(available()).isEqualTo(5);assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("ANNULLATO");
    }
    @Test void captureMismatchAndMissingConfirmationCannotCancelOrRefund(){
        fixture(true);
        assertThatThrownBy(()->refunds.request(orderId,true,false)).isInstanceOf(ResponseStatusException.class);
        when(refundGateway.capture(anyString())).thenReturn(new StripeRefundGateway.Capture("pi_refund","ch_refund",1,"eur",false,true));
        assertThatThrownBy(()->refunds.request(orderId,true,true)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds",Integer.class)).isZero();assertThat(available()).isEqualTo(4);
    }
    @Test void transactionRollbackRestoresIntentOrderAndStockTogether(){
        fixture(true);doThrow(new IllegalStateException("fixture failure")).when(mail).transitioned(any());
        try{assertThatThrownBy(()->refunds.request(orderId,true,true)).isInstanceOf(IllegalStateException.class);}
        finally{doCallRealMethod().when(mail).transitioned(any());}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds",Integer.class)).isZero();
        assertThat(available()).isEqualTo(4);assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
    }
    @Test void latePaymentOnTerminalOrderIsRefundedWithoutReopeningOrRestocking(){
        fixture(false);domain.closeUnpaid(attemptId,PaymentStatus.EXPIRED);
        jdbc.update("INSERT INTO payment_events(id,attempt_id,event_type,received_at,processed_at,outcome,session_ref,attempt_hint,order_hint,retries) VALUES('evt_late',?,'checkout.session.completed',?,?,'ANOMALY','cs_test_refund',?,?,0)",attemptId,java.sql.Timestamp.valueOf("2030-01-01 12:00:00"),java.sql.Timestamp.valueOf("2030-01-01 12:00:00"),attemptId,orderId);
        assertThat(refunds.anomalies()).hasSize(1);refunds.request(orderId,false,true);refundWorker.tick();
        providerStatus="succeeded";advance(2);refundWorker.tick();
        assertThat(refundStatus()).isEqualTo("SUCCEEDED");assertThat(attempt(orderId).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("ANNULLATO");assertThat(available()).isEqualTo(5);
        assertThat(refunds.anomalies()).isEmpty();
    }
    @Test void concurrentShippingAndCancellationHaveOneConsistentWinner() throws Exception {
        fixture(true);management.update(orderId,"IN_PREPARAZIONE");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();try{refunds.request(orderId,true,true);}catch(ResponseStatusException expected){assertThat(expected.getStatusCode().value()).isEqualTo(409);}return true;});
            var b=pool.submit(()->{start.await();try{management.update(orderId,"SPEDITO");}catch(ResponseStatusException expected){assertThat(expected.getStatusCode().value()).isEqualTo(409);}return true;});
            start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();}
        String saved=orders.findById(orderId).orElseThrow().getShipment();
        assertThat(saved).isIn("ANNULLATO","SPEDITO");
        assertThat(available()).isEqualTo("ANNULLATO".equals(saved)?5:4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds",Integer.class)).isEqualTo("ANNULLATO".equals(saved)?1:0);
    }
    @Test void adminAuthorizationCsrfAndExplicitConfirmationAreRequired() throws Exception {
        fixture(true);String path="/api/admin/stripe/orders/"+orderId+"/cancel-refund";
        mvc.perform(post(path).with(csrf()).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user(alice.getEmail())).with(csrf()).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"confirmed\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"confirmed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.refundStatus").value("REQUESTED"));
    }
}
