package com.example.pharmacyservice.service;

import com.example.pharmacyservice.config.KafkaProducerConfig;
import com.example.pharmacyservice.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Service phụ trách gửi sự kiện OrderEvent lên Kafka topic 'medicine-stock-events'.
 * Sử dụng Message Key là medicineId để đảm bảo các đơn hàng của cùng một loại thuốc
 * luôn đi vào cùng một Partition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProducerService {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    /**
     * Gửi OrderEvent lên topic medicine-stock-events với Message Key = String.valueOf(medicineId).
     *
     * @param orderEvent sự kiện đơn hàng cần gửi
     * @return CompletableFuture chứa kết quả gửi (partition, offset,...)
     */
    public CompletableFuture<SendResult<String, OrderEvent>> sendOrderEvent(OrderEvent orderEvent) {
        // Thử thách: Sử dụng Message Key là medicineId để các đơn hàng cùng một loại thuốc luôn vào cùng một partition
        String messageKey = String.valueOf(orderEvent.getMedicineId());

        log.info("Chuẩn bị gửi OrderEvent lên Kafka: topic='{}', key='{}', orderId='{}', medicineId={}, quantity={}",
                KafkaProducerConfig.MEDICINE_STOCK_TOPIC, messageKey, orderEvent.getOrderId(),
                orderEvent.getMedicineId(), orderEvent.getQuantity());

        CompletableFuture<SendResult<String, OrderEvent>> future = 
                kafkaTemplate.send(KafkaProducerConfig.MEDICINE_STOCK_TOPIC, messageKey, orderEvent);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Gửi OrderEvent thành công lên topic='{}', partition={}, offset={}, key='{}'",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        messageKey);
            } else {
                log.error("Gửi OrderEvent thất bại cho orderId='{}', lỗi: {}",
                        orderEvent.getOrderId(), ex.getMessage(), ex);
            }
        });

        return future;
    }
}
