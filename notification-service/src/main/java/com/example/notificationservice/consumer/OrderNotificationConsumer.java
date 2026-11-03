package com.example.notificationservice.consumer;

import com.example.notificationservice.event.OrderEvent;
import com.example.notificationservice.model.InvoiceNotification;
import com.example.notificationservice.service.EmailService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Consumer của notification-service:
 * Lắng nghe cùng topic 'medicine-stock-events' với group-id = 'notification-group'.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderNotificationConsumer {

    private final EmailService emailService;

    @Getter
    private final List<String> notificationLogs = Collections.synchronizedList(new ArrayList<>());

    /**
     * Khi nhận được sự kiện OrderEvent:
     * 1. Kích hoạt phương thức gửi email hóa đơn cho khách hàng
     * 2. In ra log thông báo đúng yêu cầu: "Hóa đơn cho đơn hàng [orderId] đã được gửi tới khách hàng"
     */
    @KafkaListener(
            topics = "${kafka.topic.medicine-stock-events:medicine-stock-events}",
            groupId = "${spring.kafka.consumer.group-id:notification-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderEvent(ConsumerRecord<String, OrderEvent> record) {
        OrderEvent orderEvent = record.value();

        log.info("================================================================================");
        log.info(" [NOTIFICATION-SERVICE] ĐÃ NHẬN SỰ KIỆN TỪ KAFKA BROKER!");
        log.info("-> Topic        : {}", record.topic());
        log.info("-> Consumer Group: notification-group");
        log.info("-> Partition    : {}", record.partition());
        log.info("-> Offset       : {}", record.offset());
        log.info("-> Order ID     : {}", orderEvent.getOrderId());
        log.info("-> Medicine ID  : {}", orderEvent.getMedicineId());
        log.info("-> Quantity     : {}", orderEvent.getQuantity());
        log.info("================================================================================");

        // Kích hoạt phương thức gửi hóa đơn điện tử / email
        InvoiceNotification invoice = emailService.sendInvoiceEmail(orderEvent);

        // In ra log thông báo chuẩn theo yêu cầu đề bài
        String notificationMessage = String.format("Hóa đơn cho đơn hàng [%s] đã được gửi tới khách hàng", orderEvent.getOrderId());
        log.info(">>> THÔNG BÁO: {} <<<", notificationMessage);

        notificationLogs.add(notificationMessage);
    }
}
