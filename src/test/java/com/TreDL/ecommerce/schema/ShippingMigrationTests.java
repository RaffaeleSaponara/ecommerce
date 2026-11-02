package com.TreDL.ecommerce.schema;
import org.flywaydb.core.Flyway; import org.junit.jupiter.api.Test; import java.sql.*; import java.util.UUID; import static org.assertj.core.api.Assertions.*;
class ShippingMigrationTests {
 @Test void v6PreservesOldTotalsAndSeedsNationalTariffs() throws Exception {
  String url="jdbc:h2:mem:shipping_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("5").load().migrate();
  try(Connection db=DriverManager.getConnection(url,"sa","");Statement s=db.createStatement()){
   s.executeUpdate("INSERT INTO customers(id,username,password,email,role) VALUES(1,'old','test','old@test','USER')");
   s.executeUpdate("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash) VALUES(1,1,CURRENT_TIMESTAMP,12.50,'EUR','CONFERMATO',X'01',REPEAT('a',32))");
   var migration=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("6").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
   try(ResultSet r=s.executeQuery("SELECT total,subtotal,shipping_cost,carrier,tracking_code FROM orders WHERE id=1")){assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("12.50");assertThat(r.getBigDecimal(2)).isEqualByComparingTo("12.50");assertThat(r.getBigDecimal(3)).isEqualByComparingTo("0");assertThat(r.getString(4)).isNull();assertThat(r.getString(5)).isNull();}
   try(ResultSet r=s.executeQuery("SELECT cost,free_threshold FROM shipping_settings WHERE id=1")){assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("5.90");assertThat(r.getBigDecimal(2)).isEqualByComparingTo("79.00");}
   assertThat(migration.migrate().migrationsExecuted).isZero();
  }
 }
}
