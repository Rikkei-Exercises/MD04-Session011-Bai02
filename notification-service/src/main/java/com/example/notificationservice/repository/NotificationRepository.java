package com.example.notificationservice.repository;

import com.example.notificationservice.model.InvoiceNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<InvoiceNotification, Long> {
    Optional<InvoiceNotification> findByOrderId(String orderId);
}
