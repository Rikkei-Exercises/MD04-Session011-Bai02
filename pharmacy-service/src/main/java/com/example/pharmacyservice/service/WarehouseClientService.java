package com.example.pharmacyservice.service;

import com.example.pharmacyservice.dto.StockCheckResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class WarehouseClientService {

    private final RestTemplate restTemplate;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${warehouse.service.url:http://localhost:8082}")
    private String warehouseUrl;

    // Cờ giả lập sập/chậm để test mà không phụ thuộc hạ tầng bên ngoài
    private volatile boolean simulateDown = false;
    private volatile long simulateDelayMs = 0;

    public WarehouseClientService(CircuitBreakerRegistry circuitBreakerRegistry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restTemplate = new RestTemplate(factory);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Hàm gọi sang Kho tổng (warehouse-service) để kiểm tra tồn kho.
     * Được bảo vệ bởi Resilience4j Circuit Breaker có tên "warehouseCB".
     */
    @CircuitBreaker(name = "warehouseCB", fallbackMethod = "checkStockFallback")
    public StockCheckResponse checkStock(String medicineName) {
        long startTime = System.currentTimeMillis();
        log.info("-> [GỌI KHO TỔNG] Đang kiểm tra tồn kho thuốc: '{}'", medicineName);

        // 1. Kiểm tra kịch bản giả lập lỗi nếu được kích hoạt
        if (simulateDown) {
            log.warn("!! [GIẢ LẬP SẬP] Kho tổng đang trong trạng thái sập/không phản hồi!");
            throw new RuntimeException("Warehouse service (Kho tổng) tạm thời ngừng hoạt động [Giả lập sập]!");
        }

        if (simulateDelayMs > 0) {
            try {
                Thread.sleep(simulateDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Gọi API thực tế sang warehouse-service
        String url = warehouseUrl + "/api/warehouse/stock/" + medicineName;
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            long duration = System.currentTimeMillis() - startTime;
            int stock = response.getBody() != null && response.getBody().containsKey("stock")
                    ? ((Number) response.getBody().get("stock")).intValue()
                    : 100;

            String state = getCircuitBreakerState();
            return StockCheckResponse.builder()
                    .medicineName(medicineName)
                    .warehouseStock(stock)
                    .inStock(stock > 0)
                    .status("SUCCESS")
                    .circuitBreakerState(state)
                    .message("Kiểm tra tồn kho thành công từ Warehouse Service.")
                    .executionTimeMs(duration)
                    .failFast(false)
                    .build();
        } catch (ResourceAccessException e) {
            log.error("!! [LỖI KẾT NỐI] Không thể kết nối tới Warehouse Service tại {}: {}", url, e.getMessage());
            throw new RuntimeException("Không thể kết nối đến Kho tổng (Connection refused/timeout): " + e.getMessage(), e);
        }
    }

    /**
     * Fallback method cho @CircuitBreaker(name = "warehouseCB").
     * Khi kho tổng bị lỗi hoặc khi mạch OPEN, hàm này sẽ được thực thi.
     */
    public StockCheckResponse checkStockFallback(String medicineName, Throwable throwable) {
        String state = getCircuitBreakerState();
        boolean isFailFast = throwable instanceof CallNotPermittedException;

        log.warn("<- [FALLBACK KÍCH HOẠT] Thuốc: '{}' | Trạng thái mạch: {} | Lỗi: {} | Fail-Fast: {}",
                medicineName, state, throwable.getClass().getSimpleName(), isFailFast);

        String message;
        if (isFailFast) {
            message = String.format("MẠCH ĐANG NGẮT (OPEN): Kho tổng bị sự cố! Resilience4j tự động ngắt kết nối và trả về lỗi nhanh (Fail-Fast) để chống treo máy tính tiền.");
        } else {
            message = String.format("Kho tổng không phản hồi: %s (Mạch hiện tại: %s).", throwable.getMessage(), state);
        }

        return StockCheckResponse.builder()
                .medicineName(medicineName)
                .warehouseStock(0)
                .inStock(false)
                .status(isFailFast ? "FAIL_FAST_BLOCKED" : "SERVICE_DEGRADED")
                .circuitBreakerState(state)
                .message(message)
                .executionTimeMs(0)
                .failFast(isFailFast)
                .build();
    }

    /**
     * Lấy trạng thái hiện tại của Circuit Breaker "warehouseCB"
     */
    public String getCircuitBreakerState() {
        try {
            return circuitBreakerRegistry.circuitBreaker("warehouseCB").getState().name();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    // Các phương thức hỗ trợ kiểm thử / giả lập
    public void setSimulateDown(boolean simulateDown) {
        this.simulateDown = simulateDown;
    }

    public boolean isSimulateDown() {
        return simulateDown;
    }

    public void setSimulateDelayMs(long simulateDelayMs) {
        this.simulateDelayMs = simulateDelayMs;
    }

    public long getSimulateDelayMs() {
        return simulateDelayMs;
    }

    public CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }
}
