package com.TreDL.ecommerce.service;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties="app.mail.enabled=true") @AutoConfigureMockMvc
class LegalFeaturesTests extends StockCheckoutContract {
 @Autowired WithdrawalService withdrawals;
 @Autowired ProductInformationService information;
 @Autowired ShippingService shipping;
 @Autowired OrderEmailWorker worker;
 @Autowired PlatformTransactionManager transactions;
 @Autowired MockMvc mvc;
 @MockitoBean JavaMailSender smtp;
 @BeforeEach void fixtures(){
  shipping.update(new ShippingService.Settings(new BigDecimal("5.90"),new BigDecimal("79.00")));
  when(smtp.createMimeMessage()).thenAnswer(i->new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
 }
 com.TreDL.ecommerce.dto.OrderDTO order(){carts.addProductToCart(alice,product);return checkout.checkout(alice.getEmail(),"legal",request(alice));}
 ProductInformationService.Data data(boolean verified){return new ProductInformationService.Data("MODEL-TEST","LOT-TEST","Materiale dichiarato di prova","10 mm", "Fabbricante fixture","Via fixture 1","maker@example.test","Avvertenza fixture <script>alert(1)</script>","Istruzioni fixture",new BigDecimal("12.500"),verified);}
 @Test void legalPagesShowConfiguredShippingAndNewSnapshotsOnly() throws Exception {
  var first=order();String original=orders.findById(first.id()).orElseThrow().getContractText();
  assertThat(original).contains(ContractTerms.VERSION,"14 giorni","due anni");
  shipping.update(new ShippingService.Settings(new BigDecimal("2.50"),new BigDecimal("50.00")));
  for(String route:List.of("/privacy","/cookies","/termini","/spedizioni-resi"))mvc.perform(get(route)).andExpect(status().isOk()).andExpect(content().string(containsString("BOZZA NON DEFINITIVA"))).andExpect(content().string(containsString("noindex, nofollow")));
  mvc.perform(get("/spedizioni-resi")).andExpect(content().string(containsString("2,50 EUR"))).andExpect(content().string(containsString("50,00 EUR")));
  assertThat(orders.findById(first.id()).orElseThrow().getContractText()).isEqualTo(original);
  carts.addProductToCart(bob,product);var second=checkout.checkout(bob.getEmail(),"new-terms",request(bob));
  assertThat(orders.findById(second.id()).orElseThrow().getContractText()).contains("2,50 EUR").isNotEqualTo(original);
 }
 @Test void withdrawalRequiresAuthenticationCsrfOwnershipAndExplicitConfirmation() throws Exception {
  var o=order();
  mvc.perform(get("/recesso")).andExpect(status().is3xxRedirection());
  mvc.perform(post("/recesso").with(user(alice.getEmail())).param("orderId",o.id().toString()).param("name","Alice").param("confirmed","true")).andExpect(status().isForbidden());
  mvc.perform(post("/recesso").with(user(bob.getEmail())).with(csrf()).param("orderId",o.id().toString()).param("name","Bob").param("confirmed","true")).andExpect(status().isNotFound());
  mvc.perform(post("/recesso").with(user(alice.getEmail())).with(csrf()).param("orderId",o.id().toString()).param("name","Alice")).andExpect(status().isBadRequest());
  mvc.perform(post("/recesso").with(user(alice.getEmail())).with(csrf()).param("orderId","999999").param("name","Alice").param("confirmed","true")).andExpect(status().isNotFound());
  mvc.perform(get("/recesso").with(user(alice.getEmail()))).andExpect(status().isOk()).andExpect(content().string(containsString("Conferma recesso"))).andExpect(content().string(containsString(alice.getEmail())));
  var receipt=withdrawals.submit(alice.getEmail(),o.id(),"Alice <b>test</b>",true);
  mvc.perform(get("/recesso/"+receipt.id()).with(user(bob.getEmail()))).andExpect(status().isNotFound());
  mvc.perform(get("/recesso/"+receipt.id()+"/ricevuta").with(user(bob.getEmail()))).andExpect(status().isNotFound());
  mvc.perform(get("/recesso/"+receipt.id()).with(user(alice.getEmail()))).andExpect(status().isOk()).andExpect(content().string(containsString("&lt;b&gt;test&lt;/b&gt;")));
  mvc.perform(get("/recesso/"+receipt.id()+"/ricevuta").with(user(alice.getEmail()))).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",containsString("attachment"))).andExpect(content().string(containsString(receipt.declaration())));
  mvc.perform(get("/admin/recessi").with(user(alice.getEmail()))).andExpect(status().isForbidden());
  mvc.perform(get("/admin/recessi").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().string(containsString("Alice &lt;b&gt;test&lt;/b&gt;")));
 }
 @Test void concurrentWithdrawalCreatesOneReceiptAndDoesNotChangeOrderOrStock() throws Exception {
  var o=order();int stock=available();
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{
   Callable<WithdrawalService.View> call=()->{start.await();return withdrawals.submit(alice.getEmail(),o.id(),"Alice",true);};
   var a=pool.submit(call);var b=pool.submit(call);start.countDown();
   var first=a.get(15,TimeUnit.SECONDS);assertThat(b.get(15,TimeUnit.SECONDS).id()).isEqualTo(first.id());
   assertThat(withdrawals.receipt(alice.getEmail(),first.id()).declaration()).contains("intero contratto",first.submittedAt().toString(),alice.getEmail());
  }finally{pool.shutdownNow();}
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdrawal_requests",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_notifications WHERE event_kind='WITHDRAWAL'",Integer.class)).isEqualTo(1);
  assertThat(available()).isEqualTo(stock);assertThat(orders.findById(o.id()).orElseThrow().getShipment()).isEqualTo(o.shipment());
 }
 @Test void rollbackAlsoRemovesReceiptEmailAndSmtpFailureRemainsRetryable() {
  var o=order();worker.tick();clearInvocations(smtp);
  new TransactionTemplate(transactions).executeWithoutResult(s->{withdrawals.submit(alice.getEmail(),o.id(),"Alice",true);verify(smtp,never()).send(any(MimeMessage.class));s.setRollbackOnly();});
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdrawal_requests",Integer.class)).isZero();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_notifications WHERE event_kind='WITHDRAWAL'",Integer.class)).isZero();
  var receipt=withdrawals.submit(alice.getEmail(),o.id(),"Alice",true);
  doThrow(new org.springframework.mail.MailSendException("fixture")).when(smtp).send(any(MimeMessage.class));worker.tick();
  assertThat(jdbc.queryForObject("SELECT smtp_accepted_at FROM email_notifications WHERE event_kind='WITHDRAWAL'",java.sql.Timestamp.class)).isNull();
  assertThat(jdbc.queryForObject("SELECT text_body FROM email_notifications WHERE event_kind='WITHDRAWAL'",String.class)).contains(receipt.declaration());
  jdbc.update("UPDATE email_notifications SET next_attempt_at=? WHERE event_kind='WITHDRAWAL'",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
  doNothing().when(smtp).send(any(MimeMessage.class));worker.tick();
  assertThat(jdbc.queryForObject("SELECT smtp_accepted_at FROM email_notifications WHERE event_kind='WITHDRAWAL'",java.sql.Timestamp.class)).isNotNull();
 }
 @Test void productFactsArePrivateUntilCompleteAndExplicitlyVerified() throws Exception {
  assertThat(information.editor(product.getId()).missing()).contains("materials","manufacturerEmail","warnings");
  information.save(product.getId(),data(false));assertThat(information.publicData(product.getId())).isNull();
  mvc.perform(get("/prodotti/"+product.getId())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("Avvertenza fixture"))));
  information.save(product.getId(),data(true));assertThat(information.publicData(product.getId()).modelCode()).isEqualTo("MODEL-TEST");
  mvc.perform(get("/prodotti/"+product.getId())).andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
  var incomplete=new ProductInformationService.Data(null,null,null,null,null,null,null,null,null,null,true);
  assertThatThrownBy(()->information.save(product.getId(),incomplete)).isInstanceOf(IllegalArgumentException.class);
  assertThat(information.publicData(product.getId()).modelCode()).isEqualTo("MODEL-TEST");
 }
 @Test void productInformationEditingIsAdminOnlyAndCsrfProtected() throws Exception {
  String route="/api/admin/products/"+product.getId()+"/information";
  mvc.perform(get(route).with(user(alice.getEmail()))).andExpect(status().isForbidden());
  mvc.perform(put(route).with(user("admin").roles("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(put(route).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"weightGrams\":-1}")).andExpect(status().isBadRequest());
  mvc.perform(get(route).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.missing").isNotEmpty());
 }
}
