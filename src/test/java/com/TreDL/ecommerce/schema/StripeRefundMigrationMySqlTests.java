package com.TreDL.ecommerce.schema;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
@org.testcontainers.junit.jupiter.Testcontainers
class StripeRefundMigrationMySqlTests {
 @org.testcontainers.junit.jupiter.Container static final org.testcontainers.containers.MySQLContainer<?> MYSQL=new org.testcontainers.containers.MySQLContainer<>("mysql:8.4.3").withDatabaseName("refund_upgrade").withUsername("refund_test").withPassword(UUID.randomUUID().toString()).withReuse(false).withTmpFs(java.util.Map.of("/var/lib/mysql","rw,size=1g")).withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))));
 @Test void v14PreservesHistoricalOrdersAttemptsAndRefunds() throws Exception {
  String url="jdbc:mysql://127.0.0.1:"+MYSQL.getMappedPort(3306)+"/refund_upgrade";
  Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/checkout-v2").target("11").load().migrate();
  try(var db=DriverManager.getConnection(url,MYSQL.getUsername(),MYSQL.getPassword());var s=db.createStatement()){
   s.executeUpdate("INSERT INTO customers(id,username,password,role) VALUES(1,'old','hash','USER')");
   s.executeUpdate("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash) VALUES(1,1,CURRENT_TIMESTAMP,18.40,'EUR','CONFERMATO',X'01',REPEAT('a',32))");
   s.executeUpdate("INSERT INTO payment_attempts(id,id_order,status,expected_amount,currency,created_at,expires_at,updated_at) VALUES('old-attempt',1,'CREATED',18.40,'EUR','2030-01-01 12:00:00','2030-01-01 12:30:00','2030-01-01 12:00:00')");
   s.executeUpdate("INSERT INTO payment_events(id,attempt_id,event_type,received_at) VALUES('evt_old','old-attempt','old',CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO refunds(id,attempt_id,status,amount,currency,idempotency_key,created_at,updated_at) VALUES('old-refund','old-attempt','REQUESTED',18.40,'EUR','old-refund-key',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   for(String version:java.util.List.of("12","13")){
    var step=Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/checkout-v2").target(version).load();
    assertThat(step.migrate().migrationsExecuted).isEqualTo(1);
   }
   var f=Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/checkout-v2").target("14").load();
   assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
   try(var r=s.executeQuery("SELECT attempt_id,retries,processed_at FROM payment_events WHERE id='evt_old'")){
    assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("old-attempt");assertThat(r.getInt(2)).isZero();assertThat(r.getTimestamp(3)).isNull();
   }
   s.executeUpdate("INSERT INTO payment_events(id,event_type,received_at) VALUES('evt_new','new',CURRENT_TIMESTAMP)");
   try(var r=s.executeQuery("SELECT amount,status,first_sent_at,request_json,create_blocked FROM refunds WHERE id='old-refund'")){
    assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("18.40");assertThat(r.getString(2)).isEqualTo("REQUESTED");assertThat(r.getObject(3)).isNull();assertThat(r.getString(4)).isNull();assertThat(r.getBoolean(5)).isFalse();
   }
   try(var r=s.executeQuery("SELECT total,payment_flow FROM orders WHERE id=1")){r.next();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("18.40");assertThat(r.getString(2)).isEqualTo("LEGACY");}
   try(var r=s.executeQuery("SELECT paid_verified_at,verified_charge_id FROM payment_attempts WHERE id='old-attempt'")){r.next();assertThat(r.getObject(1)).isNull();assertThat(r.getString(2)).isNull();}
   assertThat(f.migrate().migrationsExecuted).isZero();
  }
 }
}
