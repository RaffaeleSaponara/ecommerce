package com.TreDL.ecommerce.schema;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class StripeSessionMigrationTests {
 @Test void v12PreservesExistingAttemptAndStartsWithoutFrozenRequest() throws Exception {
  String url="jdbc:h2:mem:sessions_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("11").load().migrate();
  try(var db=DriverManager.getConnection(url,"sa","");var s=db.createStatement()){
   s.executeUpdate("INSERT INTO customers(id,username,password,role) VALUES(1,'old','hash','USER')");
   s.executeUpdate("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash,payment_flow) VALUES(1,1,CURRENT_TIMESTAMP,18.40,'EUR','IN_ATTESA_PAGAMENTO',X'01',REPEAT('a',32),'STRIPE_TEST')");
   s.executeUpdate("INSERT INTO payment_attempts(id,id_order,status,expected_amount,currency,created_at,expires_at,updated_at) VALUES('old-attempt',1,'CREATED',18.40,'EUR','2030-01-01 12:00:00','2030-01-01 12:30:00','2030-01-01 12:00:00')");
   var f=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").load();
   assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
   try(var r=s.executeQuery("SELECT expected_amount,status,session_request,session_started_at FROM payment_attempts")){
    assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("18.40");
    assertThat(r.getString(2)).isEqualTo("CREATED");assertThat(r.getString(3)).isNull();assertThat(r.getTimestamp(4)).isNull();
   }
   assertThat(f.migrate().migrationsExecuted).isZero();
  }
 }
}
