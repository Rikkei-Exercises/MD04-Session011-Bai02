package com.example.pharmacyservice;

import com.example.pharmacyservice.config.KafkaProducerConfig;
import com.example.pharmacyservice.dto.OrderRequest;
import com.example.pharmacyservice.event.OrderEvent;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.OrderProducerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderProducerKafkaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MedicineRepository medicineRepository;

    @MockitoBean
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Autowired
    private OrderProducerService orderProducerService;

    @BeforeEach
    void setUp() {
        // Cấu hình mock cho kafkaTemplate.send(...) trả về CompletableFuture completed
        when(kafkaTemplate.send(anyString(), anyString(), any(OrderEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // Chuẩn bị dữ liệu thuốc mẫu
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
    @DisplayName("Kiểm tra API bán thuốc /api/v1/retail/sell: Gửi OrderEvent lên Kafka và phản hồi thành công")
    void testSellMedicineApiSendsKafkaEvent() throws Exception {
        mockMvc.perform(post("/api/v1/retail/sell")
                        .param("medicineName", "Paracetamol 500mg")
                        .param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.kafkaTopic").value("medicine-stock-events"))
                .andExpect(jsonPath("$.kafkaMessageKey").exists())
                .andExpect(jsonPath("$.orderId").exists());

        // Xác nhận kafkaTemplate.send() đã được gọi với topic 'medicine-stock-events'
        verify(kafkaTemplate, atLeastOnce()).send(
                eq(KafkaProducerConfig.MEDICINE_STOCK_TOPIC),
                anyString(),
                any(OrderEvent.class)
        );
    }

    @Test
    @DisplayName("Kiểm tra API Thanh toán /api/v1/orders/checkout: Trả về thông tin thanh toán thành công và gửi OrderEvent")
    void testCheckoutApiSendsKafkaEvent() throws Exception {
        Medicine med = medicineRepository.findByName("Paracetamol 500mg").orElseThrow();
        OrderRequest request = OrderRequest.builder()
                .medicineId(med.getId())
                .medicineName(med.getName())
                .quantity(2)
                .build();

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Thanh toán thành công! Sự kiện đơn hàng đã gửi lên Kafka broker."))
                .andExpect(jsonPath("$.medicineId").value(med.getId()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.topic").value("medicine-stock-events"))
                .andExpect(jsonPath("$.messageKey").value(String.valueOf(med.getId())))
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.orderEvent.medicineId").value(med.getId()));

        // Kiểm tra kafkaTemplate.send được gọi với topic và key tương ứng
        verify(kafkaTemplate, atLeastOnce()).send(
                eq(KafkaProducerConfig.MEDICINE_STOCK_TOPIC),
                eq(String.valueOf(med.getId())),
                argThat(event -> event.getMedicineId().equals(med.getId()) && event.getQuantity() == 2)
        );
    }
}
