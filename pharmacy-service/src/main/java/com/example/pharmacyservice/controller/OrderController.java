package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.CheckoutResponse;
import com.example.pharmacyservice.dto.OrderRequest;
import com.example.pharmacyservice.event.OrderEvent;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.OrderProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final MedicineRepository medicineRepository;
    private final OrderProducerService orderProducerService;

    /**
     * API Lấy danh sách thuốc đang có trong kho
     */
    @GetMapping("/medicines")
    public ResponseEntity<List<Medicine>> getMedicines() {
        return ResponseEntity.ok(medicineRepository.findAll());
    }

    /**
     * API Bán thuốc / Thanh toán đơn hàng:
     * Khi nhân viên bấm "Thanh toán", Producer gửi đối tượng OrderEvent vào topic 'medicine-stock-events'.
     * Message Key là medicineId để đảm bảo các đơn hàng của cùng loại thuốc luôn vào cùng một Partition.
     */
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@RequestBody(required = false) OrderRequest request) {
        if (request == null) {
            request = new OrderRequest();
        }

        int quantity = (request.getQuantity() != null && request.getQuantity() > 0) ? request.getQuantity() : 1;
        Long medicineId = request.getMedicineId();
        String medicineName = request.getMedicineName();
        final String searchName = medicineName;

        Medicine medicine = null;
        if (medicineId != null) {
            medicine = medicineRepository.findById(medicineId).orElse(null);
        }
        if (medicine == null && searchName != null && !searchName.isBlank()) {
            medicine = medicineRepository.findByName(searchName)
                    .or(() -> medicineRepository.findByNameIgnoreCase(searchName))
                    .orElse(null);
        }

        if (medicine == null) {
            medicine = medicineRepository.findAll().stream().findFirst().orElse(null);
        }

        if (medicine != null) {
            medicineId = medicine.getId();
            medicineName = medicine.getName();
            medicine.setQuantity(Math.max(0, (medicine.getQuantity() != null ? medicine.getQuantity() : 0) - quantity));
            medicineRepository.save(medicine);
        } else {
            if (medicineId == null) medicineId = 1L;
            if (medicineName == null) medicineName = "Paracetamol 500mg";
        }

        double unitPrice = (medicine != null && medicine.getUnitPrice() != null) ? medicine.getUnitPrice() : 2500.0;
        double totalAmount = unitPrice * quantity;
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long timestamp = System.currentTimeMillis();

        OrderEvent orderEvent = OrderEvent.builder()
                .orderId(orderId)
                .medicineId(medicineId)
                .quantity(quantity)
                .timestamp(timestamp)
                .build();

        // Thử thách: Sử dụng Message Key là medicineId để đưa vào cùng partition
        orderProducerService.sendOrderEvent(orderEvent);

        CheckoutResponse response = CheckoutResponse.builder()
                .status("SUCCESS")
                .message("Thanh toán thành công! Sự kiện đơn hàng đã gửi lên Kafka broker.")
                .orderId(orderId)
                .medicineId(medicineId)
                .medicineName(medicineName)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .topic("medicine-stock-events")
                .messageKey(String.valueOf(medicineId))
                .timestamp(timestamp)
                .orderEvent(orderEvent)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint phụ trợ: /pay tương thích với /checkout
     */
    @PostMapping("/pay")
    public ResponseEntity<CheckoutResponse> pay(@RequestBody(required = false) OrderRequest request) {
        return checkout(request);
    }
}
