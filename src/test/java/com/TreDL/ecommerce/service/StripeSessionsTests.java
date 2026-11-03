package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripeSessionsTests extends StripeDomainTests {
    @Autowired StripeCheckoutSessions sessions;
    @MockitoBean StripeSessionGateway gateway;
    Map<String,Object> frozen;
    int creations;
    String id;
    StripeSessionGateway.Session response(Map<String,Object> params){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var meta=(Map<String,String>)params.get("metadata");
        return new StripeSessionGateway.Session("cs_test_fixture",null,1840,"eur",false,"open","unpaid",
            "https://checkout.stripe.com/c/test",1893501060L,meta);
    }
    @BeforeEach void adapter(){
        frozen=null;creations=0;
        when(gateway.create(anyMap(),anyString())).thenAnswer(call->{
            synchronized(this){
                assertThat(call.getArgument(1,String.class)).isEqualTo("3dl-session-"+id);
                var params=(Map<String,Object>)call.getArgument(0);if(frozen==null){frozen=params;creations++;}else assertThat(params).isEqualTo(frozen);return response(params);}
        });
        when(gateway.retrieve(anyString())).thenAnswer(call->response(frozen));
    }
    void order(){id=attempt(reserve().id()).getId();}
    @Test void sessionReferencesAndSnapshotsArePersistedAndReplayed(){
        order();var first=sessions.obtain(id);
        assertThat(sessions.obtain(id)).isEqualTo(first);assertThat(creations).isEqualTo(1);
        assertThat(payments.findById(id).orElseThrow().getSessionId()).isEqualTo(first.id());
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(com.TreDL.ecommerce.model.PaymentStatus.PENDING);
        assertThat(orders.findAll().get(0).getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
        verify(mail,never()).confirmed(any());
    }
    @Test void ambiguousTimeoutAndLostLocalSaveReuseFrozenRequest(){
        order();doAnswer(call->{frozen=call.getArgument(0);creations++;throw new SessionFailure(false);})
            .doAnswer(call->{assertThat(call.getArgument(0,Map.class)).isEqualTo(frozen);return response(frozen);})
            .when(gateway).create(anyMap(),anyString());
        assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
        sessions.obtain(id);assertThat(creations).isEqualTo(1);
    }
    @Test void windowAndConfigurationChangesNeverCreateAnotherSession(){
        order();doThrow(new SessionFailure(false)).when(gateway).create(anyMap(),anyString());
        assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
        when(clock.instant()).thenReturn(Instant.parse("2030-01-02T12:00:00Z"));
        clearInvocations(gateway);
        assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
        verifyNoInteractions(gateway);
    }
    @Test void temporaryConflictAndConcurrentCallsUseOneRemoteSession() throws Exception {
        order();
        var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->sessions.obtain(id));var b=pool.submit(()->sessions.obtain(id));
            assertThat(a.get(20,TimeUnit.SECONDS).id()).isEqualTo(b.get(20,TimeUnit.SECONDS).id());
            assertThat(creations).isEqualTo(1);
        }finally{pool.shutdownNow();}
        jdbc.update("UPDATE payment_attempts SET provider_session_id=NULL WHERE id=?",id);
        doThrow(new SessionFailure(true)).doAnswer(c->response(frozen)).when(gateway).create(anyMap(),anyString());
        assertThat(sessions.obtain(id).id()).isEqualTo("cs_test_fixture");
    }
    @Test void failedAssociationCanBeRecoveredWithoutNewCreation(){
        order();
        doAnswer(call->{frozen=call.getArgument(0);creations++;
            jdbc.update("UPDATE payment_attempts SET close_requested_at=CURRENT_TIMESTAMP WHERE id=?",id);
            return response(frozen);
        }).when(gateway).create(anyMap(),anyString());
        assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
        assertThat(payments.findById(id).orElseThrow().getSessionId()).isNull();
        jdbc.update("UPDATE payment_attempts SET close_requested_at=NULL WHERE id=?",id);
        doAnswer(c->{assertThat(c.getArgument(0,Map.class)).isEqualTo(frozen);return response(frozen);}).when(gateway).create(anyMap(),anyString());
        sessions.obtain(id);assertThat(creations).isEqualTo(1);
    }
    @Test void completePaidAndExpiredSessionsNeverConfirmOrRelease(){
        order();sessions.obtain(id);
        for(String status:List.of("complete","expired")){
            when(gateway.retrieve(anyString())).thenReturn(new StripeSessionGateway.Session("cs_test_fixture","pi_test",1840,"eur",false,status,
                status.equals("complete")?"paid":"unpaid",null,1893501060L,(Map<String,String>)frozen.get("metadata")));
            sessions.obtain(id);
            assertThat(orders.findAll().get(0).getShipment()).isEqualTo("IN_ATTESA_PAGAMENTO");
            assertThat(available()).isEqualTo(4);
        }
    }
    @Test void keyValidationRejectsLiveAndPublicKeys(){
        for(String key:List.of("sk_live_invalid","rk_live_invalid","pk_test_invalid",""))
            assertThatThrownBy(()->StripeSessionConfiguration.validateKey(key)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void frozenUrlsSurviveConfigurationChangesAndDifferentCredentialsAreRejected(){
        order();doThrow(new SessionFailure(false)).when(gateway).create(anyMap(),anyString());
        assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
        var changed=new StripeCheckoutSessions(transactions,gateway,clock,"https://changed.test","sk_test_fixture_only");
        org.springframework.test.util.ReflectionTestUtils.setField(changed,"em",
            org.springframework.test.util.ReflectionTestUtils.getField(sessions,"em"));
        doAnswer(call->{frozen=call.getArgument(0);
            assertThat(frozen.get("success_url")).isEqualTo("http://127.0.0.1:9085/user/orders/"+orders.findAll().get(0).getId()+"/payment/success");return response(frozen);}).when(gateway).create(anyMap(),anyString());
        changed.obtain(id);
        var wrongKey=new StripeCheckoutSessions(transactions,gateway,clock,"https://changed.test","sk_test_other_fixture");
        org.springframework.test.util.ReflectionTestUtils.setField(wrongKey,"em",
            org.springframework.test.util.ReflectionTestUtils.getField(sessions,"em"));
        clearInvocations(gateway);
        assertThatThrownBy(()->wrongKey.obtain(id)).isInstanceOf(SessionFailure.class);
        verifyNoInteractions(gateway);
    }
    @Test void ineligibleOrderIsRejectedBeforeAnyProviderCall(){
        order();jdbc.update("UPDATE orders SET payment_flow='LEGACY'");
        assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
        verifyNoInteractions(gateway);
    }
    @Test void genuineSqlFailureAfterRemoteCreationCanBeRecovered(){
        order();
        jdbc.execute("ALTER TABLE payment_attempts ADD CONSTRAINT session_save_failure CHECK (provider_session_id IS NULL)");
        try {
            assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(RuntimeException.class).hasStackTraceContaining("session_save_failure");
            assertThat(creations).isEqualTo(1);
            assertThat(payments.findById(id).orElseThrow().getSessionId()).isNull();
        } finally {jdbc.execute("ALTER TABLE payment_attempts DROP " + ("MySQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName())) ? "CHECK" : "CONSTRAINT") + " session_save_failure");}
        sessions.obtain(id);assertThat(creations).isEqualTo(1);
    }
    @Test void malformedRemoteStatesAndUrlsFailWithoutPersistingOrConfirming(){
        order();
        for(int scenario=0;scenario<3;scenario++){
            final int n=scenario;
            doAnswer(call->{
                var good=response(call.getArgument(0));
                return new StripeSessionGateway.Session(good.id(),null,good.amount(),good.currency(),false,
                    n==0?null:"open",n==1?null:"unpaid",n==2?"not a valid URI":good.url(),
                    good.expiresAt(),good.metadata());
            }).when(gateway).create(anyMap(),anyString());
            assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(SessionFailure.class);
            assertThat(payments.findById(id).orElseThrow().getSessionId()).isNull();
        }
        verify(mail,never()).confirmed(any());assertThat(available()).isEqualTo(4);
    }
    @Test void ambientTransactionIsRejectedBeforeTheGatewayIsInvoked(){
        order();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertThatThrownBy(()->sessions.obtain(id)).isInstanceOf(IllegalStateException.class);
        });
        verifyNoInteractions(gateway);
    }
}
