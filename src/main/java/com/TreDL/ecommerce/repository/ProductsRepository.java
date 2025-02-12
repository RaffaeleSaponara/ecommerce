package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.Products;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductsRepository extends JpaRepository<Products, Long> {
    List<Products> findAll();
}
