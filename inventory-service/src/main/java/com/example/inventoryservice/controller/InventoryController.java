package com.example.inventoryservice.controller;

import com.example.inventoryservice.consumer.OrderEventConsumer;
import com.example.inventoryservice.event.OrderEvent;
import com.example.inventoryservice.model.Medicine;
import com.example.inventoryservice.model.OrderRecord;
import com.example.inventoryservice.repository.MedicineRepository;
import com.example.inventoryservice.repository.OrderRecordRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final MedicineRepository medicineRepository;
    private final OrderRecordRepository orderRecordRepository;
    private final OrderEventConsumer orderEventConsumer;

    @GetMapping("/medicines")
    public ResponseEntity<List<Medicine>> getMedicines() {
        return ResponseEntity.ok(medicineRepository.findAll());
    }

    @GetMapping("/orders")
    public ResponseEntity<List<OrderRecord>> getOrders() {
        return ResponseEntity.ok(orderRecordRepository.findAll());
    }

    @GetMapping("/logs")
    public ResponseEntity<List<String>> getLogs() {
        return ResponseEntity.ok(orderEventConsumer.getProcessLogs());
    }

    /**
     * API giả lập nhận OrderEvent trực tiếp để kiểm thử logic Consumer mà không phụ thuộc Kafka Broker.
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulateEvent(@RequestBody OrderEvent orderEvent) {
        ConsumerRecord<String, OrderEvent> record = new ConsumerRecord<>(
                "medicine-stock-events", 0, 0, String.valueOf(orderEvent.getMedicineId()), orderEvent
        );
        orderEventConsumer.consumeOrderEvent(record);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("message", "Consumer đã xử lý sự kiện: thêm đơn hàng và trừ stock thành công!");
        result.put("orderId", orderEvent.getOrderId());
        result.put("updatedMedicines", medicineRepository.findAll());
        return ResponseEntity.ok(result);
    }
}
