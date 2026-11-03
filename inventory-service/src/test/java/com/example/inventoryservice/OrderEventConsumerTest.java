package com.example.inventoryservice;

import com.example.inventoryservice.consumer.OrderEventConsumer;
import com.example.inventoryservice.event.OrderEvent;
import com.example.inventoryservice.model.Medicine;
import com.example.inventoryservice.model.OrderRecord;
import com.example.inventoryservice.repository.MedicineRepository;
import com.example.inventoryservice.repository.OrderRecordRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OrderEventConsumerTest {

    @Autowired
    private OrderEventConsumer orderEventConsumer;

    @Autowired
    private MedicineRepository medicineRepository;

    @Autowired
    private OrderRecordRepository orderRecordRepository;

    private Long testMedicineId;

    @BeforeEach
    void setUp() {
        orderRecordRepository.deleteAll();

        // Khởi tạo thuốc Paracetamol với stock = 100
        Optional<Medicine> medOpt = medicineRepository.findByNameIgnoreCase("Paracetamol 500mg");
        Medicine med;
        if (medOpt.isPresent()) {
            med = medOpt.get();
            med.setStock(100);
            medicineRepository.save(med);
        } else {
            med = medicineRepository.save(Medicine.builder()
                    .name("Paracetamol 500mg")
                    .stock(100)
                    .unitPrice(2500.0)
                    .build());
        }
        testMedicineId = med.getId();
    }

    @Test
    @DisplayName("Consumer nhận OrderEvent: Thêm mới đơn hàng và thực hiện lệnh UPDATE medicines SET stock = stock - quantity")
    void testConsumeOrderEvent_AddsOrderAndDeductsStock() {
        String testOrderId = "ORD-" + System.currentTimeMillis();
        int buyQuantity = 5;

        OrderEvent event = OrderEvent.builder()
                .orderId(testOrderId)
                .medicineId(testMedicineId)
                .quantity(buyQuantity)
                .timestamp(System.currentTimeMillis())
                .build();

        ConsumerRecord<String, OrderEvent> record = new ConsumerRecord<>(
                "medicine-stock-events", 0, 100L, String.valueOf(testMedicineId), event
        );

        // Gọi Consumer xử lý sự kiện
        orderEventConsumer.consumeOrderEvent(record);

        // 1. Kiểm tra đơn hàng đã được thêm mới vào Database
        Optional<OrderRecord> orderOpt = orderRecordRepository.findByOrderId(testOrderId);
        assertTrue(orderOpt.isPresent(), "Đơn hàng mới phải được lưu vào bảng orders");
        assertEquals(testOrderId, orderOpt.get().getOrderId());
        assertEquals(testMedicineId, orderOpt.get().getMedicineId());
        assertEquals(buyQuantity, orderOpt.get().getQuantity());
        assertEquals("COMPLETED", orderOpt.get().getStatus());

        // 2. Kiểm tra tồn kho đã bị trừ từ 100 xuống 95 (100 - 5 = 95)
        Medicine updatedMed = medicineRepository.findById(testMedicineId).orElseThrow();
        assertEquals(95, updatedMed.getStock(),
                "Stock của thuốc phải giảm đi 5 (từ 100 xuống 95) theo lệnh UPDATE medicines SET stock = stock - quantity");
    }

    @Test
    @DisplayName("Consumer không xử lý trùng lặp khi nhận cùng một orderId lần 2 (Idempotency)")
    void testConsumeOrderEvent_AvoidsDuplicateProcessing() {
        String testOrderId = "ORD-DUPLICATE-001";
        int buyQuantity = 10;

        OrderEvent event = OrderEvent.builder()
                .orderId(testOrderId)
                .medicineId(testMedicineId)
                .quantity(buyQuantity)
                .timestamp(System.currentTimeMillis())
                .build();

        ConsumerRecord<String, OrderEvent> record = new ConsumerRecord<>(
                "medicine-stock-events", 0, 101L, String.valueOf(testMedicineId), event
        );

        // Lần 1
        orderEventConsumer.consumeOrderEvent(record);
        Medicine medAfterFirst = medicineRepository.findById(testMedicineId).orElseThrow();
        assertEquals(90, medAfterFirst.getStock());

        // Lần 2 (trùng orderId)
        orderEventConsumer.consumeOrderEvent(record);
        Medicine medAfterSecond = medicineRepository.findById(testMedicineId).orElseThrow();
        // Tồn kho vẫn phải là 90, không bị trừ 2 lần
        assertEquals(90, medAfterSecond.getStock(), "Không được trừ trùng lặp khi cùng một orderId nhận lại");
    }
}
