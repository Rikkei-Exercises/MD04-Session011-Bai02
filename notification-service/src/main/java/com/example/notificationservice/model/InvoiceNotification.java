package com.example.notificationservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "invoice_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderId;

    private Long medicineId;

    private Integer quantity;

    private String recipientEmail;

    private String emailSubject;

    @Lob
    private String emailContent;

    private String status; // SENT, SIMULATED, FAILED

    private LocalDateTime sentAt;
}
