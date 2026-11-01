package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.StockCheckResponse;
import com.example.pharmacyservice.service.WarehouseClientService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
     * Khi kho tổng bị sự cố/sập, sau 1 số lần lỗi mạch sẽ chuyển OPEN và Fail-fast ngay lập tức.
     */
    @GetMapping("/check-warehouse-stock")
    public ResponseEntity<StockCheckResponse> checkWarehouseStock(
            @RequestParam(defaultValue = "Paracetamol 500mg") String medicineName) {
        
        long startTime = System.currentTimeMillis();
        StockCheckResponse response = warehouseClientService.checkStock(medicineName);
        long totalTime = System.currentTimeMillis() - startTime;
        response.setExecutionTimeMs(totalTime);

        HttpStatus status = response.isFailFast() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
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
