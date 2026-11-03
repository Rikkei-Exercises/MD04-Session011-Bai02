package com.example.pharmacyservice.dto;

import com.example.pharmacyservice.event.OrderEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutResponse {
    private String status;
    private String message;
    private String orderId;
    private Long medicineId;
    private String medicineName;
    private Integer quantity;
    private Double totalAmount;
    private String topic;
    private String messageKey;
    private Integer partition;
    private Long offset;
    private Long timestamp;
    private OrderEvent orderEvent;
}
