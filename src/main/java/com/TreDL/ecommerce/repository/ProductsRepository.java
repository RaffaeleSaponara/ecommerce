package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.Products;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.math.BigDecimal;

@Repository
public interface ProductsRepository extends JpaRepository<Products, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Products p where p.id = :id")
    java.util.Optional<Products> findByIdForUpdate(@Param("id") long id);

    List<Products> findAll();

    // LOCATE treats %, _ and backslashes as literal name characters.
    @Query("""
            select p from Products p
            where (:q is null or locate(lower(:q), lower(p.nome)) > 0)
              and (:category is null or lower(p.categoria) = lower(:category))
              and (:minPrice is null or p.prezzo >= :minPrice)
              and (:maxPrice is null or p.prezzo <= :maxPrice)
            """)
    List<Products> findFiltered(@Param("q") String q, @Param("category") String category,
                               @Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice);
}
