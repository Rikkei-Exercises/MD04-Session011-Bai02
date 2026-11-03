package com.example.inventoryservice.repository;

import com.example.inventoryservice.model.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {
    Optional<OrderRecord> findByOrderId(String orderId);
}
