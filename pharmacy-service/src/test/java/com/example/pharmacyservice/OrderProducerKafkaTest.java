package com.example.pharmacyservice;

import com.example.pharmacyservice.config.KafkaProducerConfig;
import com.example.pharmacyservice.controller.OrderController;
import com.example.pharmacyservice.controller.RetailController;
import com.example.pharmacyservice.dto.CheckoutResponse;
import com.example.pharmacyservice.dto.OrderRequest;
import com.example.pharmacyservice.event.OrderEvent;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.OrderProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class OrderProducerKafkaTest {

    @Autowired
    private MedicineRepository medicineRepository;

    @MockitoBean
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Autowired
    private OrderProducerService orderProducerService;

    @Autowired
    private OrderController orderController;

    @Autowired
    private RetailController retailController;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(anyString(), anyString(), any(OrderEvent.class)))
                .thenReturn(new CompletableFuture<>());

        if (medicineRepository.findByName("Paracetamol 500mg").isEmpty()) {
            medicineRepository.save(Medicine.builder()
                    .name("Paracetamol 500mg")
                    .quantity(100)
                    .unitPrice(2500.0)
                    .build());
        }
    }

    @Test
    @DisplayName("Kiểm tra OrderProducerService gửi OrderEvent vào topic 'medicine-stock-events' với Message Key là medicineId")
    void testSendOrderEventWithMedicineIdAsKey() {
        Long testMedicineId = 101L;
        OrderEvent event = OrderEvent.builder()
                .orderId("ORD-TEST-001")
                .medicineId(testMedicineId)
                .quantity(5)
                .timestamp(System.currentTimeMillis())
                .build();

        orderProducerService.sendOrderEvent(event);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);

        verify(kafkaTemplate, times(1)).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        // Kiểm tra đúng topic
        assertEquals(KafkaProducerConfig.MEDICINE_STOCK_TOPIC, topicCaptor.getValue(),
                "Topic phải là 'medicine-stock-events'");

        // Kiểm tra đúng message key (medicineId)
        assertEquals(String.valueOf(testMedicineId), keyCaptor.getValue(),
                "Message Key phải là medicineId để đưa vào cùng partition");

        // Kiểm tra nội dung event
        OrderEvent capturedEvent = eventCaptor.getValue();
        assertEquals("ORD-TEST-001", capturedEvent.getOrderId());
        assertEquals(testMedicineId, capturedEvent.getMedicineId());
        assertEquals(5, capturedEvent.getQuantity());
        assertNotNull(capturedEvent.getTimestamp());
    }

    @Test
    @DisplayName("Kiểm tra API bán thuốc sellMedicine: Gửi OrderEvent lên Kafka và phản hồi thành công")
    void testSellMedicineApiSendsKafkaEvent() {
        ResponseEntity<Map<String, Object>> response = retailController.sellMedicine("Paracetamol 500mg", 3);

        assertNotNull(response.getBody());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals("medicine-stock-events", response.getBody().get("kafkaTopic"));
        assertNotNull(response.getBody().get("kafkaMessageKey"));
        assertNotNull(response.getBody().get("orderId"));

        verify(kafkaTemplate, atLeastOnce()).send(
                eq(KafkaProducerConfig.MEDICINE_STOCK_TOPIC),
                anyString(),
                any(OrderEvent.class)
        );
    }

    @Test
    @DisplayName("Kiểm tra API Thanh toán checkout: Trả về thông tin thanh toán thành công và gửi OrderEvent")
    void testCheckoutApiSendsKafkaEvent() {
        Medicine med = medicineRepository.findByName("Paracetamol 500mg").orElseThrow();
        OrderRequest request = OrderRequest.builder()
                .medicineId(med.getId())
                .medicineName(med.getName())
                .quantity(2)
                .build();

        ResponseEntity<CheckoutResponse> response = orderController.checkout(request);

        CheckoutResponse checkoutRes = response.getBody();
        assertNotNull(checkoutRes);
        assertEquals("SUCCESS", checkoutRes.getStatus());
        assertTrue(checkoutRes.getMessage().contains("Thanh toán thành công"));
        assertEquals(med.getId(), checkoutRes.getMedicineId());
        assertEquals(2, checkoutRes.getQuantity());
        assertEquals("medicine-stock-events", checkoutRes.getTopic());
        assertEquals(String.valueOf(med.getId()), checkoutRes.getMessageKey());
        assertNotNull(checkoutRes.getOrderId());
        assertNotNull(checkoutRes.getOrderEvent());

        verify(kafkaTemplate, atLeastOnce()).send(
                eq(KafkaProducerConfig.MEDICINE_STOCK_TOPIC),
                eq(String.valueOf(med.getId())),
                argThat(event -> event.getMedicineId().equals(med.getId()) && event.getQuantity() == 2)
        );
    }
}
