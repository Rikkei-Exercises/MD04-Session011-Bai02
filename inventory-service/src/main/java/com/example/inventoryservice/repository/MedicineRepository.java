package com.example.inventoryservice.repository;

import com.example.inventoryservice.model.Medicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    Optional<Medicine> findByNameIgnoreCase(String name);

    /**
     * Lệnh UPDATE stock xuống Database theo đúng yêu cầu đề bài:
     * UPDATE medicines SET stock = stock - quantity
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE medicines SET stock = stock - :quantity WHERE id = :medicineId", nativeQuery = true)
    int deductStock(@Param("medicineId") Long medicineId, @Param("quantity") Integer quantity);
}
