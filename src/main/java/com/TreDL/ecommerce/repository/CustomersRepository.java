package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.Customers;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomersRepository extends JpaRepository<Customers, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from Customers customer where customer.id = :id")
    Optional<Customers> findByIdForUpdate(@Param("id") Long id);

    Optional<Customers> findByUsername(String username);

    Optional<Customers> findByEmail(String email);
}
