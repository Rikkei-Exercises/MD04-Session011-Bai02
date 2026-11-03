package com.example.notificationservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Event nhận từ Kafka topic 'medicine-stock-events'.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent implements Serializable {
    private String orderId;
    private Long medicineId;
    private Integer quantity;
    private Long timestamp;
    private String customerEmail;
}
