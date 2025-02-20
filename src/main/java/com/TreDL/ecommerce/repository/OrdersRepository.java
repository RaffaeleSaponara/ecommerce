package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrdersRepository extends JpaRepository<Orders, Long> {

}
