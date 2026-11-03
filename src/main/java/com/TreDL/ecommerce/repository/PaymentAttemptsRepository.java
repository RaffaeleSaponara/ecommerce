package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentAttemptsRepository extends JpaRepository<PaymentAttempt,String> {
    Optional<PaymentAttempt> findByOrderId(Long orderId);
}
