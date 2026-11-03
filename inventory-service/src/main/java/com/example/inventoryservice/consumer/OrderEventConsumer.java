package com.example.inventoryservice.consumer;

import com.example.inventoryservice.event.OrderEvent;
import com.example.inventoryservice.model.Medicine;
import com.example.inventoryservice.model.OrderRecord;
import com.example.inventoryservice.repository.MedicineRepository;
import com.example.inventoryservice.repository.OrderRecordRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Consumer lắng nghe sự kiện từ Kafka topic 'medicine-stock-events'.
 * Group ID: 'inventory-group' đảm bảo tính nhất quán và không xử lý trùng lặp.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final MedicineRepository medicineRepository;
    private final OrderRecordRepository orderRecordRepository;

    @Getter
    private final List<String> processLogs = Collections.synchronizedList(new ArrayList<>());

    /**
     * Lắng nghe topic medicine-stock-events.
     * Khi nhận được OrderEvent:
     * 1. Đọc thông tin sự kiện
     * 2. Thêm mới đơn hàng vào bảng orders
     * 3. Thực hiện lệnh: UPDATE medicines SET stock = stock - quantity
     */
    @Transactional
    @KafkaListener(
            topics = "${kafka.topic.medicine-stock-events:medicine-stock-events}",
            groupId = "${spring.kafka.consumer.group-id:inventory-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderEvent(ConsumerRecord<String, OrderEvent> record) {
        String key = record.key();
        OrderEvent orderEvent = record.value();
        int partition = record.partition();
        long offset = record.offset();

        log.info("================================================================================");
        log.info(" [INVENTORY-SERVICE CONSUMER] ĐÃ NHẬN SỰ KIỆN TỪ KAFKA BROKER!");
        log.info("-> Topic        : {}", record.topic());
        log.info("-> Partition    : {} (Key={})", partition, key);
        log.info("-> Offset       : {}", offset);
        log.info("-> Order ID     : {}", orderEvent.getOrderId());
        log.info("-> Medicine ID  : {}", orderEvent.getMedicineId());
        log.info("-> Quantity     : {}", orderEvent.getQuantity());
        log.info("-> Timestamp    : {}", orderEvent.getTimestamp());
        log.info("================================================================================");

        // 1. Kiểm tra đơn hàng có bị trùng lặp không (idempotency check)
        if (orderRecordRepository.findByOrderId(orderEvent.getOrderId()).isPresent()) {
            log.warn("Đơn hàng {} đã được xử lý trước đó, bỏ qua để tránh trùng lặp.", orderEvent.getOrderId());
            return;
        }

        // 2. Thêm mới đơn hàng vào Database
        OrderRecord orderRecord = OrderRecord.builder()
                .orderId(orderEvent.getOrderId())
                .medicineId(orderEvent.getMedicineId())
                .quantity(orderEvent.getQuantity())
                .timestamp(orderEvent.getTimestamp())
                .createdAt(LocalDateTime.now())
                .status("COMPLETED")
                .build();
        orderRecordRepository.save(orderRecord);
        log.info("[1/2] Đã thêm mới đơn hàng thành công: orderId='{}'", orderEvent.getOrderId());

        // 3. Thực hiện lệnh Database: UPDATE medicines SET stock = stock - quantity
        log.info("[2/2] Thực hiện lệnh DB: UPDATE medicines SET stock = stock - {} WHERE id = {}",
                orderEvent.getQuantity(), orderEvent.getMedicineId());
        
        int rowsUpdated = medicineRepository.deductStock(orderEvent.getMedicineId(), orderEvent.getQuantity());

        // Đọc lại tồn kho mới nhất để ghi log
        Medicine updatedMed = medicineRepository.findById(orderEvent.getMedicineId()).orElse(null);
        Integer remainingStock = (updatedMed != null) ? updatedMed.getStock() : null;

        String logSummary = String.format(
                "[%s] Nhận Order %s | Thuốc ID: %d | Số lượng trừ: %d | Tồn kho mới: %s | Partition: %d (Rows updated: %d)",
                LocalDateTime.now(), orderEvent.getOrderId(), orderEvent.getMedicineId(),
                orderEvent.getQuantity(), remainingStock, partition, rowsUpdated
        );
        processLogs.add(logSummary);

        log.info("CẬP NHẬT TỒN KHO THÀNH CÔNG: {}", logSummary);
    }
}
