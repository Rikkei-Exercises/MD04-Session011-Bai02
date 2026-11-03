package com.example.notificationservice.controller;

import com.example.notificationservice.consumer.OrderNotificationConsumer;
import com.example.notificationservice.event.OrderEvent;
import com.example.notificationservice.model.InvoiceNotification;
import com.example.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final OrderNotificationConsumer notificationConsumer;

    @GetMapping
    public ResponseEntity<List<InvoiceNotification>> getAllInvoices() {
        return ResponseEntity.ok(notificationRepository.findAll());
    }

    @GetMapping("/logs")
    public ResponseEntity<List<String>> getLogs() {
        return ResponseEntity.ok(notificationConsumer.getNotificationLogs());
    }

    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulate(@RequestBody OrderEvent event) {
        ConsumerRecord<String, OrderEvent> record = new ConsumerRecord<>(
                "medicine-stock-events", 0, 0, String.valueOf(event.getMedicineId()), event
        );
        notificationConsumer.consumeOrderEvent(record);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("message", "Đã kích hoạt phương thức gửi email và in log thông báo thành công!");
        result.put("orderId", event.getOrderId());
        result.put("notifications", notificationRepository.findAll());
        return ResponseEntity.ok(result);
    }
}
