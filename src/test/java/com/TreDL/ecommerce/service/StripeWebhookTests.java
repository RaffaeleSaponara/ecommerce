package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"payments.stripe.enabled=true","payments.stripe.secret-key=sk_test_fixture_only","payments.stripe.scheduler.enabled=false","payments.stripe.webhook-secret=whsec_fixture"})
class StripeWebhookTests extends StripeDomainTests {
 @Autowired StripeWebhookInbox inbox;
 @Autowired StripeReconciliation worker;
 @MockitoBean StripeSessionGateway gateway;
 String attemptId;long orderId;String session="cs_test_fixture";
 StripeSessionGateway.Session remote;
 @BeforeEach void provider(){
  when(gateway.retrieve(anyString())).thenAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return remote;});
 }
 void pending(){
  orderId=reserve().id();attemptId=attempt(orderId).getId();
  remote=new StripeSessionGateway.Session(session,"pi_fixture",1840,"eur",false,"complete","paid",null,1893501060L,Map.of("order_id",""+orderId,"attempt_id",attemptId));
  jdbc.update("UPDATE payment_attempts SET provider_session_id=? WHERE id=?",session,attemptId);
 }
 String payload(String id,String type){
  return "{\"id\":\""+id+"\",\"object\":\"event\",\"livemode\":false,\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\""+session+"\",\"metadata\":{\"order_id\":\""+orderId+"\",\"attempt_id\":\""+attemptId+"\"}}}}";
 }
 String sign(String body) throws Exception{
  return signAt(body,Instant.now().getEpochSecond());
 }
 String signAt(String body,long timestamp) throws Exception{
  String t=""+timestamp;Mac mac=Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("whsec_fixture".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
  return "t="+t+",v1="+HexFormat.of().formatHex(mac.doFinal((t+"."+body).getBytes(StandardCharsets.UTF_8)));
 }
 void receive(String id,String type) throws Exception {String p=payload(id,type);inbox.accept(p,sign(p));}
 @Test void signedWebhookNeedsNoSessionOrCsrfAndPersistsBeforeProcessing() throws Exception {
  pending();String p=payload("evt_first","checkout.session.completed");
  mvc.perform(post("/api/stripe/webhook").contentType("application/json").content(p).header("Stripe-Signature",sign(p))).andExpect(status().isOk());
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
  mvc.perform(post("/api/stripe/webhook").contentType("application/json").content(p).header("Stripe-Signature","invalid")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/stripe/webhook").contentType("application/json").content(p+" ").header("Stripe-Signature",sign(p))).andExpect(status().isBadRequest());
  receive("evt_first","checkout.session.completed");
  worker.tick();worker.tick();
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
  assertThat(available()).isEqualTo(4);verify(mail,times(1)).confirmed(any());
 }
 @Test void duplicateOutOfOrderAndClosureIntentCannotUndoSuccessfulPayment() throws Exception {
  pending();domain.requestClosure(attemptId);
  receive("evt_success","checkout.session.completed");receive("evt_old","checkout.session.expired");
  worker.tick();
  assertThat(attempt(orderId).getStatus()).isEqualTo(com.TreDL.ecommerce.model.PaymentStatus.SUCCEEDED);
  assertThat(available()).isEqualTo(4);
  verify(mail,times(1)).confirmed(any());
 }
 @Test void negativeEventIsNotProofAndProcessingPaymentNeverReleasesStock() throws Exception {
  pending();remote=new StripeSessionGateway.Session(session,"pi_fixture",1840,"eur",false,"expired","unpaid",null,1893501060L,remote.metadata());
  when(gateway.paymentState("pi_fixture")).thenReturn("processing");
  receive("evt_expired","checkout.session.expired");worker.tick();
  assertThat(available()).isEqualTo(4);
  assertThat(jdbc.queryForObject("SELECT processed_at FROM payment_events WHERE id='evt_expired'",java.sql.Timestamp.class)).isNull();
  when(clock.instant()).thenReturn(Instant.parse("2030-01-01T12:10:00Z"));
  when(gateway.paymentState("pi_fixture")).thenReturn("canceled");
  worker.tick();worker.tick();
  assertThat(available()).isEqualTo(5);
  assertThat(attempt(orderId).getStatus()).isEqualTo(com.TreDL.ecommerce.model.PaymentStatus.EXPIRED);
 }
 @Test void providerOutageAndExpiredLeaseRecoverAfterRestart() throws Exception {
  pending();receive("evt_retry","checkout.session.completed");
  doThrow(new SessionFailure(false)).when(gateway).retrieve(anyString());worker.tick();
  assertThat(available()).isEqualTo(4);
  jdbc.update("UPDATE payment_events SET lease_token='crashed',lease_until=?,next_attempt_at=? WHERE id='evt_retry'",
    java.sql.Timestamp.valueOf("2030-01-01 12:01:00"),java.sql.Timestamp.valueOf("2030-01-01 12:01:00"));
  when(clock.instant()).thenReturn(Instant.parse("2030-01-01T12:10:00Z"));
  doAnswer(c->remote).when(gateway).retrieve(anyString());
  var restarted=new StripeReconciliation(jdbc,transactions,clock,gateway,
    org.springframework.test.util.ReflectionTestUtils.getField(worker,"sessions") instanceof StripeCheckoutSessions s?s:null,domain);
  org.springframework.test.util.ReflectionTestUtils.setField(restarted,"em",org.springframework.test.util.ReflectionTestUtils.getField(worker,"em"));
  restarted.tick();
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
 }
 @Test void monetaryMismatchRemainsExplicitAnomaly() throws Exception{
  pending();remote=new StripeSessionGateway.Session(session,"pi_fixture",1,"eur",false,"complete","paid",null,1893501060L,remote.metadata());
  receive("evt_bad","checkout.session.completed");worker.tick();
  assertThat(jdbc.queryForObject("SELECT outcome FROM payment_events WHERE id='evt_bad'",String.class)).isEqualTo("ANOMALY");
  assertThat(available()).isEqualTo(4);verify(mail,never()).confirmed(any());
 }
 @Test void concurrentWorkersAndCancellationRequestConfirmOnce() throws Exception {
  pending();receive("evt_race","checkout.session.completed");
  var pool=Executors.newFixedThreadPool(3);
  try{
   var a=pool.submit(worker::tick);var b=pool.submit(worker::tick);var c=pool.submit(()->domain.requestClosure(attemptId));
   a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);c.get(20,TimeUnit.SECONDS);
  }finally{pool.shutdownNow();}
  assertThat(available()).isEqualTo(4);verify(mail,times(1)).confirmed(any());
 }
 @Test void missingWebhookIsRecoveredFromPersistentAttempt() {
  pending();when(clock.instant()).thenReturn(Instant.parse("2030-01-01T13:00:00Z"));worker.tick();
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
 }
 @Test void expiredSessionWithRetryableIntentRequiresProviderCancellationBeforeRelease() throws Exception{
  pending();remote=new StripeSessionGateway.Session(session,"pi_fixture",1840,"eur",false,"expired","unpaid",null,1893501060L,remote.metadata());
  when(gateway.paymentState("pi_fixture")).thenAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return "requires_payment_method";});
  when(gateway.cancelPayment("pi_fixture")).thenAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return "canceled";});
  receive("evt_cancel","checkout.session.expired");worker.tick();
  assertThat(available()).isEqualTo(5);verify(gateway).cancelPayment("pi_fixture");
 }
 @Test void signedReceiptRepairsSessionCreatedButNotAssociatedLocally() throws Exception {
  pending();jdbc.update("UPDATE payment_attempts SET provider_session_id=NULL WHERE id=?",attemptId);
  receive("evt_unbound","checkout.session.completed");worker.tick();
  assertThat(attempt(orderId).getSessionId()).isEqualTo(session);
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
  assertThat(available()).isEqualTo(4);
 }
 @Test void processingRollbackKeepsReceiptAndOrderRecoverable() throws Exception {
  pending();receive("evt_rollback","checkout.session.completed");
  jdbc.execute("ALTER TABLE payment_events ADD CONSTRAINT event_processing_failure CHECK (processed_at IS NULL)");
  try{
   worker.tick();
   assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
   assertThat(attempt(orderId).getStatus()).isEqualTo(com.TreDL.ecommerce.model.PaymentStatus.CREATED);
   assertThat(jdbc.queryForObject("SELECT processed_at FROM payment_events WHERE id='evt_rollback'",java.sql.Timestamp.class)).isNull();
   assertThat(available()).isEqualTo(4);
  }finally{
   String product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
   jdbc.execute("ALTER TABLE payment_events DROP "+("MySQL".equals(product)?"CHECK":"CONSTRAINT")+" event_processing_failure");
  }
  when(clock.instant()).thenReturn(Instant.parse("2030-01-01T12:02:00Z"));worker.tick();
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
  assertThat(available()).isEqualTo(4);
 }
 @Test void completedUnpaidWithoutIntentIsStillUncertain() throws Exception {
  pending();remote=new StripeSessionGateway.Session(session,null,1840,"eur",false,"complete","unpaid",null,1893501060L,remote.metadata());
  receive("evt_uncertain","checkout.session.async_payment_failed");worker.tick();
  assertThat(available()).isEqualTo(4);
  assertThat(orders.findById(orderId).orElseThrow().getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
  assertThat(jdbc.queryForObject("SELECT processed_at FROM payment_events WHERE id='evt_uncertain'",java.sql.Timestamp.class)).isNull();
 }
 @Test void terminalPaymentNeverCausesProviderCancellationFromAnOlderNegativeEvent() throws Exception {
  pending();domain.confirm(attemptId,new java.math.BigDecimal("18.40"),"EUR");
  remote=new StripeSessionGateway.Session(session,"pi_fixture",1840,"eur",false,"expired","unpaid",null,1893501060L,remote.metadata());
  when(gateway.paymentState("pi_fixture")).thenReturn("requires_payment_method");
  receive("evt_stale","checkout.session.expired");worker.tick();
  verify(gateway,never()).cancelPayment(anyString());verify(gateway,never()).expire(anyString());
  assertThat(available()).isEqualTo(4);
 }
 @Test void staleSignatureAndOversizedBodyAreRejectedAndIrrelevantEventsReturn204() throws Exception {
  pending();String p=payload("evt_security","checkout.session.completed");
  mvc.perform(post("/api/stripe/webhook").contentType("application/json").content(p)
    .header("Stripe-Signature",signAt(p,Instant.now().minusSeconds(600).getEpochSecond()))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/stripe/webhook").contentType("application/json").content("x".repeat(262145))).andExpect(status().isPayloadTooLarge());
  String other=payload("evt_other","customer.updated");
  mvc.perform(post("/api/stripe/webhook").contentType("application/json").content(other).header("Stripe-Signature",sign(other)))
    .andExpect(status().isNoContent());
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_events",Long.class)).isZero();
 }
 @Test void failedReceiptPersistenceNeverReturns200() throws Exception {
  pending();String p=payload("evt_nocommit","checkout.session.completed");
  jdbc.execute("ALTER TABLE payment_events ADD CONSTRAINT receipt_failure CHECK (event_type='blocked')");
  try{
   mvc.perform(post("/api/stripe/webhook").contentType("application/json").content(p).header("Stripe-Signature",sign(p))).andExpect(status().isServiceUnavailable());
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_events",Long.class)).isZero();
  }finally{
   String product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
   jdbc.execute("ALTER TABLE payment_events DROP "+("MySQL".equals(product)?"CHECK":"CONSTRAINT")+" receipt_failure");
  }
 }
 @Test void unavailableOldAttemptDoesNotStayAtTheHeadOfEverySchedulerBatch(){
  pending();when(clock.instant()).thenReturn(Instant.parse("2030-01-01T13:00:00Z"));
  doThrow(new SessionFailure(false)).when(gateway).retrieve(anyString());worker.tick();
  java.sql.Timestamp next=jdbc.queryForObject("SELECT next_reconcile_at FROM payment_attempts WHERE id=?",java.sql.Timestamp.class,attemptId);
  assertThat(next.toLocalDateTime()).isAfter(java.time.LocalDateTime.parse("2030-01-01T13:00:00"));
  assertThat(available()).isEqualTo(4);
 }
}
