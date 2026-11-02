package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="app.mail.enabled=true")
class OrderEmailTests extends StockCheckoutContract {
    @Autowired OrderManagementService management;
    @Autowired ShippingService shipping;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JavaMailSender smtp;
    final List<MimeMessage> messages=new ArrayList<>();
    @BeforeEach void mail() {
        messages.clear();
        when(smtp.createMimeMessage()).thenAnswer(i->new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(orders.count()).isPositive();
            MimeMessage message=i.getArgument(0);message.saveChanges();messages.add(message);return null;
        }).when(smtp).send(any(MimeMessage.class));
    }
    OrderDTO confirmed() {
        carts.addProductToCart(alice,product);return checkout.checkout(alice.getEmail(),"mail-order",request(alice));
    }
    String body(Part part,String type) throws Exception {
        if(part.isMimeType(type))return part.getContent().toString();
        if(part.getContent() instanceof Multipart multipart) {
            StringBuilder text=new StringBuilder();
            for(int i=0;i<multipart.getCount();i++)text.append(body(multipart.getBodyPart(i),type));
            return text.toString();
        }
        return "";
    }
    @Test void confirmationUsesSnapshotsEscapesHtmlAndReplayDoesNotSendAgain() throws Exception {
        jdbc.update("UPDATE products SET nome=? WHERE id=?","Jig <img src=x onerror=alert(1)>",product.getId());
        carts.addProductToCart(alice,product);carts.addProductToCart(alice,product);var request=request(alice);
        var order=checkout.checkout(alice.getEmail(),"snapshot-mail",request);
        assertThat(messages).hasSize(1);var message=messages.get(0);
        assertThat(message.getSubject()).contains("3DL","#"+order.id(),"confermato");
        String text=body(message,"text/plain"),html=body(message,"text/html");
        assertThat(text).contains("× 2","25,00 €","5,90 €","30,90 €","Mario Rossi","Via Roma 1","00100 Roma (RM)","CONFERMATO","Data:");
        assertThat(html).contains("&lt;img").doesNotContain("<img src=x");
        assertThat(text).doesNotContain("idempotency","requestHash",alice.getPassword());
        jdbc.update("UPDATE products SET nome='Changed',prezzo=99 WHERE id=?",product.getId());
        checkout.checkout(alice.getEmail(),"snapshot-mail",request);assertThat(messages).hasSize(1);
    }
    @Test void rollbackAfterCheckoutAndFailedCheckoutSendNothing() {
        carts.addProductToCart(alice,product);var request=request(alice);
        new TransactionTemplate(transactions).execute(status->{checkout.checkout(alice.getEmail(),"rolled-back",request);assertThat(messages).isEmpty();status.setRollbackOnly();return null;});
        assertThat(messages).isEmpty();assertThat(orders.count()).isZero();assertThat(available()).isEqualTo(5);
        stock(0);conflict(()->checkout.checkout(alice.getEmail(),"failed",request));assertThat(messages).isEmpty();
    }
    @Test void cancellationSendsOnceAndKeepsRestoredStock() throws Exception {
        var order=confirmed();management.update(order.id(),"ANNULLATO");management.update(order.id(),"ANNULLATO");
        assertThat(messages).hasSize(2);assertThat(messages.get(1).getSubject()).contains("annullato");
        assertThat(body(messages.get(1),"text/plain")).contains("#"+order.id(),"Data:","non attesta un rimborso effettuato");
        assertThat(available()).isEqualTo(5);
    }
    @Test void shipmentSendsOnlyOnItsTransitionAndSnapshotsTrackingWithoutLinks() throws Exception {
        var order=confirmed();management.update(order.id(),"IN_PREPARAZIONE");assertThat(messages).hasSize(1);
        shipping.tracking(order.id(),new ShippingService.Tracking("Corriere <b>test</b>","TRACK-123"));assertThat(messages).hasSize(1);
        management.update(order.id(),"SPEDITO");management.update(order.id(),"SPEDITO");assertThat(messages).hasSize(2);
        assertThat(body(messages.get(1),"text/plain")).contains("TRACK-123","Corriere <b>test</b>");
        assertThat(body(messages.get(1),"text/html")).contains("&lt;b&gt;test&lt;/b&gt;").doesNotContain("href=","<b>test</b>");
        shipping.tracking(order.id(),new ShippingService.Tracking("Later","LATER-CODE"));management.update(order.id(),"CONSEGNATO");assertThat(messages).hasSize(2);
    }
    @Test void shipmentWithoutTrackingDoesNotInventDetails() throws Exception {
        var order=confirmed();management.update(order.id(),"IN_PREPARAZIONE");management.update(order.id(),"SPEDITO");
        assertThat(body(messages.get(1),"text/plain")).doesNotContain("Corriere:","Codice di tracciamento:","http");
    }
    @Test void smtpFailureDoesNotRollbackOrTriggerAnotherSendOnReplayOrRepeatedCancellation() {
        doThrow(new MailSendException("transport detail must stay private")).when(smtp).send(any(MimeMessage.class));
        carts.addProductToCart(alice,product);var request=request(alice);
        var order=checkout.checkout(alice.getEmail(),"smtp-failure",request);
        assertThat(orders.count()).isEqualTo(1);assertThat(available()).isEqualTo(4);
        checkout.checkout(alice.getEmail(),"smtp-failure",request);verify(smtp,times(1)).send(any(MimeMessage.class));
        management.update(order.id(),"ANNULLATO");management.update(order.id(),"ANNULLATO");
        assertThat(available()).isEqualTo(5);assertThat(orders.findById(order.id()).orElseThrow().getShipment()).isEqualTo("ANNULLATO");
        verify(smtp,times(2)).send(any(MimeMessage.class));
    }
}
