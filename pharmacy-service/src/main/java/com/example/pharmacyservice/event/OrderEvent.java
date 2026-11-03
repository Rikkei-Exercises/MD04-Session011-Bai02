package com.example.pharmacyservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Event đại diện cho sự kiện đơn hàng bán thuốc.
 * Được gửi lên Kafka topic 'medicine-stock-events'.
 * Message Key là medicineId để đảm bảo các đơn hàng của cùng một loại thuốc luôn đi vào cùng một Partition.
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
}
