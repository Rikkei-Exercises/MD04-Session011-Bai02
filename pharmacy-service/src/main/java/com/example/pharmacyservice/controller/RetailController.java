package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.StockCheckResponse;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.service.WarehouseClientService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/retail")
@RequiredArgsConstructor
public class RetailController {

    private final WarehouseClientService warehouseClientService;

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
     * API Bán lẻ thuốc trực tiếp - Thực hiện giao dịch bán hàng.
     * Khi kho tổng sập, hệ thống vẫn cho phép bán dựa trên tồn kho tại chỗ.
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
            warehouseClientService.getMedicineRepository().findByName(medicineName)
                    .or(() -> warehouseClientService.getMedicineRepository().findByNameIgnoreCase(medicineName))
                    .ifPresent(med -> {
                        med.setQuantity(Math.max(0, med.getQuantity() - quantity));
                        warehouseClientService.getMedicineRepository().save(med);
                    });

            result.put("status", "SUCCESS");
            result.put("remainingStock", Math.max(0, stockInfo.getAvailableStock() - quantity));
            result.put("transactionStatus", "Giao dịch bán lẻ thành công!");
            result.put("note", "Hệ thống tiếp tục phục vụ khách hàng bình thường mà không bị gián đoạn hay nhận mã lỗi 500.");
            return ResponseEntity.ok(result);
        } else {
            result.put("status", "OUT_OF_STOCK");
            result.put("remainingStock", stockInfo.getAvailableStock());
            result.put("transactionStatus", "Số lượng tồn kho không đủ để bán.");
            return ResponseEntity.badRequest().body(result);
        }
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
