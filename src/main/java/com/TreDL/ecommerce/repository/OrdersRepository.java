package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrdersRepository extends JpaRepository<Orders, Long> {
    // Cancelled orders no longer contribute to confirmed order totals.
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(o.total), 0) from Orders o where o.shipment <> 'ANNULLATO'")
    java.math.BigDecimal sumConfirmedOrderTotals();
    java.util.List<Orders> findByCustomerEmailOrderByDateDescIdDesc(String email);
    java.util.Optional<Orders> findByCustomerIdAndIdempotencyKey(Long customerId, byte[] idempotencyKey);

}
