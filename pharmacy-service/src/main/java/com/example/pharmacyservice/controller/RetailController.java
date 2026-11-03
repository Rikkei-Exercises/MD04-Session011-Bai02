package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.CheckoutResponse;
import com.example.pharmacyservice.dto.OrderRequest;
import com.example.pharmacyservice.dto.StockCheckResponse;
import com.example.pharmacyservice.event.OrderEvent;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.service.OrderProducerService;
import com.example.pharmacyservice.service.WarehouseClientService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/retail")
@RequiredArgsConstructor
public class RetailController {

    private final WarehouseClientService warehouseClientService;
    private final OrderProducerService orderProducerService;

    /**
     * API Bán lẻ - Kiểm tra hàng tồn từ Kho tổng (warehouse-service).
     * Được bảo vệ bởi Circuit Breaker (warehouseCB).
     * Khi kho tổng bị sự cố/sập hoặc mạch ngắt (OPEN):
     * Fallback checkWarehouseFallback() tự động chuyển sang sử dụng tồn kho tại chỗ (LOCAL_INVENTORY),
     * trả về HTTP 200 OK kèm thông báo hướng dẫn, không bao giờ trả về lỗi 500.
     */
    @GetMapping("/check-warehouse-stock")
    public ResponseEntity<StockCheckResponse> checkWarehouseStock(
            @RequestParam(defaultValue = "Paracetamol 500mg") String medicineName) {
        
        long startTime = System.currentTimeMillis();
        StockCheckResponse response = warehouseClientService.checkWarehouse(medicineName);
        long totalTime = System.currentTimeMillis() - startTime;
        if (response.getExecutionTimeMs() == 0) {
            response.setExecutionTimeMs(totalTime);
        }

        // Luôn trả về 200 OK với dữ liệu tồn kho (kho tổng hoặc tại chỗ) để tiếp tục giao dịch
        return ResponseEntity.ok(response);
    }

    /**
     * API Bán lẻ thuốc trực tiếp - Thực hiện giao dịch bán hàng và thanh toán.
     * Khi gọi API bán thuốc, Producer gửi đối tượng OrderEvent vào topic medicine-stock-events
     * với Message Key là medicineId để các đơn hàng cùng loại thuốc luôn vào cùng một Partition.
     */
    @PostMapping("/sell")
    public ResponseEntity<Map<String, Object>> sellMedicine(
            @RequestParam(defaultValue = "Paracetamol 500mg") String medicineName,
            @RequestParam(defaultValue = "2") int quantity) {

        StockCheckResponse stockInfo = warehouseClientService.checkWarehouse(medicineName);
        Map<String, Object> result = new HashMap<>();
        result.put("medicineName", medicineName);
        result.put("requestedQuantity", quantity);
        result.put("circuitBreakerState", stockInfo.getCircuitBreakerState());
        result.put("stockSource", stockInfo.getStockSource());
        result.put("stockMessage", stockInfo.getMessage());

        if (stockInfo.getAvailableStock() >= quantity) {
            // Giảm tồn kho tại chỗ nếu có trong DB
            var medicineOpt = warehouseClientService.getMedicineRepository().findByName(medicineName)
                    .or(() -> warehouseClientService.getMedicineRepository().findByNameIgnoreCase(medicineName));

            Long medicineId = 1L;
            if (medicineOpt.isPresent()) {
                Medicine med = medicineOpt.get();
                med.setQuantity(Math.max(0, med.getQuantity() - quantity));
                warehouseClientService.getMedicineRepository().save(med);
                if (med.getId() != null) {
                    medicineId = med.getId();
                }
            }

            // Tạo OrderEvent và gửi lên Kafka topic 'medicine-stock-events' với Message Key = medicineId
            String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            OrderEvent orderEvent = OrderEvent.builder()
                    .orderId(orderId)
                    .medicineId(medicineId)
                    .quantity(quantity)
                    .timestamp(System.currentTimeMillis())
                    .build();

            orderProducerService.sendOrderEvent(orderEvent);

            result.put("status", "SUCCESS");
            result.put("orderId", orderId);
            result.put("medicineId", medicineId);
            result.put("orderEvent", orderEvent);
            result.put("kafkaTopic", "medicine-stock-events");
            result.put("kafkaMessageKey", String.valueOf(medicineId));
            result.put("remainingStock", Math.max(0, stockInfo.getAvailableStock() - quantity));
            result.put("transactionStatus", "Thanh toán thành công! Sự kiện đơn hàng đã gửi lên Kafka broker.");
            result.put("note", "Message Key là medicineId (" + medicineId + ") đảm bảo đơn hàng đi vào cùng partition.");
            return ResponseEntity.ok(result);
        } else {
            result.put("status", "OUT_OF_STOCK");
            result.put("remainingStock", stockInfo.getAvailableStock());
            result.put("transactionStatus", "Số lượng tồn kho không đủ để bán.");
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * API Thanh toán (Checkout) nhận OrderRequest:
     * Gửi OrderEvent lên Kafka topic medicine-stock-events với Message Key là medicineId.
     */
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkoutMedicine(@RequestBody OrderRequest request) {
        int quantity = (request.getQuantity() != null && request.getQuantity() > 0) ? request.getQuantity() : 1;
        Long medicineId = request.getMedicineId();
        String medicineName = request.getMedicineName();
        final String searchName = medicineName;

        Medicine medicine = null;
        if (medicineId != null) {
            medicine = warehouseClientService.getMedicineRepository().findById(medicineId).orElse(null);
        }
        if (medicine == null && searchName != null && !searchName.isBlank()) {
            medicine = warehouseClientService.getMedicineRepository().findByName(searchName)
                    .or(() -> warehouseClientService.getMedicineRepository().findByNameIgnoreCase(searchName))
                    .orElse(null);
        }

        if (medicine == null) {
            // Mặc định thuốc mẫu nếu không tìm thấy
            medicine = warehouseClientService.getMedicineRepository().findAll().stream().findFirst().orElse(null);
        }

        if (medicine != null) {
            medicineId = medicine.getId();
            medicineName = medicine.getName();
            medicine.setQuantity(Math.max(0, (medicine.getQuantity() != null ? medicine.getQuantity() : 0) - quantity));
            warehouseClientService.getMedicineRepository().save(medicine);
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

        // Gửi lên Kafka topic medicine-stock-events với Message Key là medicineId
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
     * API Giả lập trạng thái Kho tổng (Sập / Bình thường / Phản hồi chậm).
     * Giúp kiểm thử Circuit Breaker mà không cần chạy thêm service bên ngoài.
     */
    @PostMapping("/simulate-warehouse")
    public ResponseEntity<Map<String, Object>> simulateWarehouse(
            @RequestParam(required = false) Boolean down,
            @RequestParam(required = false) Long delayMs) {

        if (down != null) {
            warehouseClientService.setSimulateDown(down);
        }
        if (delayMs != null) {
            warehouseClientService.setSimulateDelayMs(delayMs);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("simulateDown", warehouseClientService.isSimulateDown());
        result.put("simulateDelayMs", warehouseClientService.getSimulateDelayMs());
        result.put("message", warehouseClientService.isSimulateDown()
                ? "ĐÃ GIẢ LẬP KHO TỔNG BỊ SẬP (Warehouse is DOWN)"
                : "Kho tổng đang hoạt động bình thường");
        result.put("currentCircuitState", warehouseClientService.getCircuitBreakerState());
        return ResponseEntity.ok(result);
    }

    /**
     * API Kiểm tra trạng thái hiện tại của Circuit Breaker "warehouseCB".
     */
    @GetMapping("/circuit-breaker-status")
    public ResponseEntity<Map<String, Object>> getCircuitBreakerStatus() {
        Map<String, Object> status = new HashMap<>();
        try {
            CircuitBreaker cb = warehouseClientService.getCircuitBreakerRegistry().circuitBreaker("warehouseCB");
            status.put("name", cb.getName());
            status.put("state", cb.getState().name());
            status.put("failureRate", cb.getMetrics().getFailureRate());
            status.put("numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls());
            status.put("numberOfSuccessfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
            status.put("numberOfBufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            status.put("failureRateThreshold", cb.getCircuitBreakerConfig().getFailureRateThreshold() + "%");
            status.put("waitDurationInOpenState", cb.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1) + "ms");
            status.put("minimumNumberOfCalls", cb.getCircuitBreakerConfig().getMinimumNumberOfCalls());
            status.put("slidingWindowSize", cb.getCircuitBreakerConfig().getSlidingWindowSize());
        } catch (Exception e) {
            status.put("error", e.getMessage());
            status.put("state", warehouseClientService.getCircuitBreakerState());
        }
        return ResponseEntity.ok(status);
    }

    /**
     * API Reset Circuit Breaker về trạng thái ban đầu (CLOSED).
     */
    @PostMapping("/circuit-breaker/reset")
    public ResponseEntity<Map<String, Object>> resetCircuitBreaker() {
        warehouseClientService.getCircuitBreakerRegistry().circuitBreaker("warehouseCB").reset();
        Map<String, Object> result = new HashMap<>();
        result.put("state", warehouseClientService.getCircuitBreakerState());
        result.put("message", "Đã reset Circuit Breaker về trạng thái CLOSED");
        return ResponseEntity.ok(result);
    }
}
