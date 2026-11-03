package com.TreDL.ecommerce.schema;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
@org.testcontainers.junit.jupiter.Testcontainers
class StripeEventMigrationMySqlTests {
 @org.testcontainers.junit.jupiter.Container static final org.testcontainers.containers.MySQLContainer<?> MYSQL=new org.testcontainers.containers.MySQLContainer<>("mysql:8.4.3").withDatabaseName("event_upgrade").withUsername("event_test").withPassword(UUID.randomUUID().toString()).withReuse(false).withTmpFs(java.util.Map.of("/var/lib/mysql","rw,size=1g")).withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))));
 @Test void v13PreservesExistingEventsAndAllowsUncorrelatedDurableReceipts() throws Exception {
  String url="jdbc:mysql://127.0.0.1:"+MYSQL.getMappedPort(3306)+"/event_upgrade";
  Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/checkout-v2").target("12").load().migrate();
  try(var db=DriverManager.getConnection(url,MYSQL.getUsername(),MYSQL.getPassword());var s=db.createStatement()){
   s.executeUpdate("INSERT INTO customers(id,username,password,role) VALUES(1,'old','hash','USER')");
   s.executeUpdate("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash) VALUES(1,1,CURRENT_TIMESTAMP,18.40,'EUR','CONFERMATO',X'01',REPEAT('a',32))");
   s.executeUpdate("INSERT INTO payment_attempts(id,id_order,status,expected_amount,currency,created_at,expires_at,updated_at) VALUES('old-attempt',1,'CREATED',18.40,'EUR','2030-01-01 12:00:00','2030-01-01 12:30:00','2030-01-01 12:00:00')");
   s.executeUpdate("INSERT INTO payment_events(id,attempt_id,event_type,received_at) VALUES('evt_old','old-attempt','old',CURRENT_TIMESTAMP)");
   var f=Flyway.configure().dataSource(url,MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/checkout-v2").load();
   assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
   try(var r=s.executeQuery("SELECT attempt_id,retries,processed_at FROM payment_events WHERE id='evt_old'")){
    assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("old-attempt");assertThat(r.getInt(2)).isZero();assertThat(r.getTimestamp(3)).isNull();
   }
   s.executeUpdate("INSERT INTO payment_events(id,event_type,received_at) VALUES('evt_new','new',CURRENT_TIMESTAMP)");
   assertThat(f.migrate().migrationsExecuted).isZero();
  }
 }
}
