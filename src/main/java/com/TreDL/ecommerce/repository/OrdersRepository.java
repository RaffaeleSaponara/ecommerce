package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrdersRepository extends JpaRepository<Orders, Long> {
    // Cancelled orders no longer contribute to confirmed order totals.
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(o.total), 0) from Orders o where o.shipment not in ('ANNULLATO','IN_ATTESA_PAGAMENTO') and (o.paymentFlow = 'LEGACY' or exists (select p.id from PaymentAttempt p where p.order = o and p.status = 'SUCCEEDED'))")
    java.math.BigDecimal sumConfirmedOrderTotals();
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "statusHistory")
    java.util.List<Orders> findByCustomerEmailOrderByDateDescIdDesc(String email);
    // Scalar snapshots avoid loading products or one collection per order.
    @org.springframework.data.jpa.repository.Query("select new com.TreDL.ecommerce.dto.OrderHistoryLine(i.order.id, i.productName, i.quantity, i.unitPrice) from OrderItem i where i.order.customer.email = :email order by i.order.id, i.lineNumber")
    java.util.List<com.TreDL.ecommerce.dto.OrderHistoryLine> historyLines(@org.springframework.data.repository.query.Param("email") String email);
    java.util.Optional<Orders> findByCustomerIdAndIdempotencyKey(Long customerId, byte[] idempotencyKey);

}
