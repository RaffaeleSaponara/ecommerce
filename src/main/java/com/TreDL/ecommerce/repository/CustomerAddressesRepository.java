package com.TreDL.ecommerce.repository;
import com.TreDL.ecommerce.model.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CustomerAddressesRepository extends JpaRepository<CustomerAddress,Long> {
    List<CustomerAddress> findByCustomerIdOrderByIdAsc(Long customerId);
    Optional<CustomerAddress> findByIdAndCustomerId(Long id,Long customerId);
}
