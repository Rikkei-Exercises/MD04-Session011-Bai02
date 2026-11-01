package com.example.pharmacyservice;

import com.example.pharmacyservice.dto.StockCheckResponse;
import com.example.pharmacyservice.service.WarehouseClientService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WarehouseCircuitBreakerTest {

    @Autowired
    private WarehouseClientService warehouseClientService;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = warehouseClientService.getCircuitBreakerRegistry().circuitBreaker("warehouseCB");
        circuitBreaker.reset();
        warehouseClientService.setSimulateDown(false);
    }

    @Test
    @DisplayName("Cấu hình CircuitBreaker warehouseCB: failure-rate-threshold=50%, wait-duration-in-open-state=20s")
    void testCircuitBreakerConfiguration() {
        assertNotNull(circuitBreaker, "Circuit Breaker warehouseCB phải được khởi tạo");
        assertEquals(50.0f, circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold(),
                "failure-rate-threshold phải bằng 50%");
        assertEquals(20, circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1) / 1000,
                "wait-duration-in-open-state phải là 20 giây (20000ms)");
    }

    @Test
    @DisplayName("Khi giả lập warehouse-service bị sập, sau một số lần lỗi, mạch chuyển sang OPEN và Fail-fast nhanh")
    void testCircuitBreakerOpensAndFailsFastWhenWarehouseDown() {
        // Trạng thái ban đầu phải là CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState(),
                "Trạng thái ban đầu của mạch phải là CLOSED");

        // Giả lập warehouse-service bị sập
        warehouseClientService.setSimulateDown(true);

        // Gọi 4 lần lỗi (đạt minimumNumberOfCalls = 4, tỉ lệ lỗi = 100% > 50%)
        for (int i = 1; i <= 4; i++) {
            StockCheckResponse resp = warehouseClientService.checkStock("Paracetamol 500mg");
            assertNotNull(resp);
            assertFalse(resp.isInStock());
        }

        // Sau 4 lần lỗi (100% failure rate > 50%), mạch phải tự động chuyển sang OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState(),
                "Sau các lần gọi lỗi liên tiếp, mạch phải chuyển sang OPEN!");

        // Lần gọi thứ 5: Vì mạch đang OPEN, Resilience4j phải ngắt mạch ngay lập tức (Fail-fast)
        long start = System.currentTimeMillis();
        StockCheckResponse failFastResp = warehouseClientService.checkStock("Paracetamol 500mg");
        long executionTime = System.currentTimeMillis() - start;

        // Kiểm tra phản hồi Fail-fast
        assertTrue(failFastResp.isFailFast(), "Phản hồi phải kích hoạt chế độ Fail-Fast!");
        assertEquals("OPEN", failFastResp.getCircuitBreakerState(), "Mạch phải ở trạng thái OPEN!");
        assertTrue(failFastResp.getMessage().contains("MẠCH ĐANG NGẮT (OPEN)"),
                "Thông điệp phải thông báo mạch đang ngắt!");
        assertTrue(executionTime < 50, "Fail-fast phải trả về gần như tức thì (< 50ms), tránh treo máy tính tiền!");
    }
}
