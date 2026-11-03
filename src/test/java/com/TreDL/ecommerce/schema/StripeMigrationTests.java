package com.TreDL.ecommerce.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class StripeMigrationTests {
    protected String url(){return "jdbc:h2:mem:stripe_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";}
    protected String username(){return "sa";}
    protected String password(){return "";}
    @Test void v11PreservesLegacyOrdersAndEnforcesPaymentConstraints() throws Exception {
        String url=url();
        Flyway.configure().dataSource(url,username(),password()).locations("classpath:db/checkout-v2").target("10").load().migrate();
        try(var db=DriverManager.getConnection(url,username(),password());var sql=db.createStatement()) {
            sql.executeUpdate("INSERT INTO customers(id,username,email,password,role) VALUES(1,'legacy','legacy@test.it','unchanged','ADMIN')");
            sql.executeUpdate("INSERT INTO products(id,nome,prezzo,stock) VALUES(1,'Original',12.50,4)");
            sql.executeUpdate("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash,subtotal,shipping_cost) VALUES(1,1,CURRENT_TIMESTAMP,18.40,'EUR','CONFERMATO',X'01',REPEAT('a',32),12.50,5.90)");
            sql.executeUpdate("INSERT INTO order_items(id_order,line_number,id_product,product_name,unit_price,quantity) VALUES(1,1,1,'Historical name',12.50,1)");
            var migration=Flyway.configure().dataSource(url,username(),password()).locations("classpath:db/checkout-v2").target("11").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();
            try(var row=sql.executeQuery("SELECT total,ship_status,payment_flow,stock_deducted,subtotal,shipping_cost FROM orders WHERE id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getBigDecimal(1)).isEqualByComparingTo("18.40");
                assertThat(row.getString(2)).isEqualTo("CONFERMATO");assertThat(row.getString(3)).isEqualTo("LEGACY");assertThat(row.getBoolean(4)).isTrue();
                assertThat(row.getBigDecimal(5)).isEqualByComparingTo("12.50");assertThat(row.getBigDecimal(6)).isEqualByComparingTo("5.90");
            }
            for(String table:new String[]{"payment_attempts","payment_events","refunds"})
                try(var row=sql.executeQuery("SELECT COUNT(*) FROM "+table)){assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isZero();}
            try(var row=sql.executeQuery("SELECT product_name,unit_price,quantity FROM order_items WHERE id_order=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("Historical name");assertThat(row.getBigDecimal(2)).isEqualByComparingTo("12.50");assertThat(row.getInt(3)).isEqualTo(1);
            }
            assertThatThrownBy(()->sql.executeUpdate("UPDATE orders SET payment_flow='UNKNOWN' WHERE id=1")).isInstanceOf(SQLException.class);
            sql.executeUpdate("UPDATE orders SET payment_flow='STRIPE_TEST',ship_status='IN_ATTESA_PAGAMENTO' WHERE id=1");
            sql.executeUpdate("INSERT INTO payment_attempts(id,id_order,status,expected_amount,currency,created_at,expires_at,updated_at) VALUES('attempt-1',1,'CREATED',18.40,'EUR','2030-01-01 12:00:00','2030-01-01 12:30:00','2030-01-01 12:00:00')");
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO payment_attempts(id,id_order,status,expected_amount,currency,created_at,expires_at,updated_at) SELECT 'attempt-2',id_order,status,expected_amount,currency,created_at,expires_at,updated_at FROM payment_attempts")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("UPDATE payment_attempts SET expected_amount=-1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("UPDATE payment_attempts SET status='UNKNOWN'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("UPDATE payment_attempts SET currency='eur'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("UPDATE payment_attempts SET expires_at=created_at")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("DELETE FROM orders WHERE id=1")).isInstanceOf(SQLException.class);
            sql.executeUpdate("INSERT INTO payment_events(id,attempt_id,event_type,received_at) VALUES('evt_1','attempt-1','test',CURRENT_TIMESTAMP)");
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO payment_events(id,attempt_id,event_type,received_at) VALUES('evt_1','attempt-1','test',CURRENT_TIMESTAMP)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO payment_events(id,attempt_id,event_type,received_at) VALUES('evt_missing','missing','test',CURRENT_TIMESTAMP)")).isInstanceOf(SQLException.class);
            sql.executeUpdate("INSERT INTO refunds(id,attempt_id,status,amount,currency,idempotency_key,created_at,updated_at) VALUES('refund-1','attempt-1','REQUESTED',18.40,'EUR','refund-key',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            assertThatThrownBy(()->sql.executeUpdate("UPDATE refunds SET amount=-1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("UPDATE refunds SET status='UNKNOWN'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO refunds(id,attempt_id,status,amount,currency,idempotency_key,created_at,updated_at) SELECT 'refund-2',attempt_id,status,amount,currency,'new-key',created_at,updated_at FROM refunds")).isInstanceOf(SQLException.class);
            assertThat(migration.migrate().migrationsExecuted).isZero();
        }
    }
}
