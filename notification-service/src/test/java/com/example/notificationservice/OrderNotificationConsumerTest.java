package com.example.notificationservice;

import com.example.notificationservice.consumer.OrderNotificationConsumer;
import com.example.notificationservice.event.OrderEvent;
import com.example.notificationservice.model.InvoiceNotification;
import com.example.notificationservice.repository.NotificationRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OrderNotificationConsumerTest {

    @Autowired
    private OrderNotificationConsumer notificationConsumer;

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        notificationConsumer.getNotificationLogs().clear();
    }

    @Test
    @DisplayName("Notification Consumer nhận OrderEvent: In log thông báo và kích hoạt gửi hóa đơn email")
    void testConsumeOrderEvent_PrintsLogAndDispatchesEmail() {
        String testOrderId = "ORD-NOTIF-999";
        Long medicineId = 1L;
        int quantity = 3;

        OrderEvent event = OrderEvent.builder()
                .orderId(testOrderId)
                .medicineId(medicineId)
                .quantity(quantity)
                .timestamp(System.currentTimeMillis())
                .customerEmail("customer@test.com")
                .build();

        ConsumerRecord<String, OrderEvent> record = new ConsumerRecord<>(
                "medicine-stock-events", 0, 10L, String.valueOf(medicineId), event
        );

        // Kích hoạt Consumer
        notificationConsumer.consumeOrderEvent(record);

        // 1. Kiểm tra log thông báo đúng định dạng yêu cầu đề bài
        String expectedLog = String.format("Hóa đơn cho đơn hàng [%s] đã được gửi tới khách hàng", testOrderId);
        assertTrue(notificationConsumer.getNotificationLogs().contains(expectedLog),
                "Phải in log thông báo: 'Hóa đơn cho đơn hàng [" + testOrderId + "] đã được gửi tới khách hàng'");

        // 2. Kiểm tra hóa đơn điện tử được lưu vào Database và đã kích hoạt phương thức gửi email
        Optional<InvoiceNotification> invoiceOpt = notificationRepository.findByOrderId(testOrderId);
        assertTrue(invoiceOpt.isPresent(), "Hóa đơn điện tử phải được lưu vào bảng invoice_notifications");
        InvoiceNotification invoice = invoiceOpt.get();
        assertEquals(testOrderId, invoice.getOrderId());
        assertEquals(medicineId, invoice.getMedicineId());
        assertEquals(quantity, invoice.getQuantity());
        assertEquals("customer@test.com", invoice.getRecipientEmail());
        assertNotNull(invoice.getEmailContent());
        assertTrue(invoice.getEmailContent().contains(testOrderId));
        assertNotNull(invoice.getSentAt());
    }
}
