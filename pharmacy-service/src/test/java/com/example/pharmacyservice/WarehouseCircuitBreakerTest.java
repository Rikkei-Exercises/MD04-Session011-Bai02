package com.example.pharmacyservice;

import com.example.pharmacyservice.dto.StockCheckResponse;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
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

    @Autowired
    private MedicineRepository medicineRepository;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = warehouseClientService.getCircuitBreakerRegistry().circuitBreaker("warehouseCB");
        circuitBreaker.reset();
        warehouseClientService.setSimulateDown(false);

        // Khởi tạo dữ liệu mẫu nếu chưa có
        if (medicineRepository.findByName("Paracetamol 500mg").isEmpty()) {
            medicineRepository.save(Medicine.builder()
                    .name("Paracetamol 500mg")
                    .quantity(100)
                    .unitPrice(2500.0)
                    .build());
        }
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
    @DisplayName("Hàm checkWarehouseFallback(Exception e) trả về thông báo và dữ liệu tồn kho cục bộ chuẩn xác")
    void testCheckWarehouseFallbackDirectCall() {
        Exception testException = new RuntimeException("Kho tổng sập đột ngột");

        // Gọi trực tiếp hàm fallback checkWarehouseFallback(Exception e)
        StockCheckResponse fallbackResp = warehouseClientService.checkWarehouseFallback(testException);

        assertNotNull(fallbackResp);
        assertEquals("Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch",
                fallbackResp.getMessage(), "Thông báo trả về phải đúng chính xác yêu cầu");
        assertEquals("LOCAL_INVENTORY", fallbackResp.getStockSource(), "Nguồn tồn kho phải là LOCAL_INVENTORY");
        assertTrue(fallbackResp.getAvailableStock() > 0, "Số lượng tồn kho tại chỗ phải lớn hơn 0");
        assertTrue(fallbackResp.isAllowSale(), "Phải cho phép tiếp tục bán hàng dựa trên số lượng tồn kho tại chỗ");
    }

    @Test
    @DisplayName("Khi kho tổng lỗi và mạch OPEN, fallbackMethod được kích hoạt, cho phép bán hàng bằng tồn kho tại chỗ thay vì báo lỗi 500")
    void testFallbackActivatedWhenWarehouseDownAndCircuitOpens() {
        // Trạng thái ban đầu: CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        // Giả lập warehouse-service bị sập
        warehouseClientService.setSimulateDown(true);

        // Gọi các lần đầu (mỗi lần bị lỗi, fallbackMethod tự động xử lý trả về thông báo cục bộ thay vì ném lỗi 500)
        for (int i = 1; i <= 4; i++) {
            StockCheckResponse resp = warehouseClientService.checkWarehouse("Paracetamol 500mg");
            assertNotNull(resp);
            assertEquals("Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch",
                    resp.getMessage());
            assertEquals("LOCAL_INVENTORY", resp.getStockSource());
            assertTrue(resp.isAllowSale(), "Vẫn cho phép tiếp tục bán hàng với tồn kho tại chỗ");
            assertTrue(resp.getAvailableStock() > 0);
        }

        // Sau 4 lần lỗi liên tiếp (100% failure rate > 50%), mạch ngắt sang OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState(),
                "Circuit Breaker phải chuyển sang trạng thái OPEN");

        // Khi mạch OPEN, gọi lại hàm: Resilience4j ngắt mạch ngay lập tức (Fail-fast),
        // fallbackMethod vẫn cung cấp thông tin tồn kho tại chỗ gần như tức thì (< 50ms)
        long start = System.currentTimeMillis();
        StockCheckResponse openResp = warehouseClientService.checkWarehouse("Paracetamol 500mg");
        long duration = System.currentTimeMillis() - start;

        assertEquals("OPEN", openResp.getCircuitBreakerState());
        assertTrue(openResp.isFailFast(), "Phải kích hoạt cờ Fail-fast");
        assertTrue(duration < 50, "Thời gian phản hồi phải siêu nhanh (< 50ms) để không làm treo máy tính tiền");
        assertEquals("Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch",
                openResp.getMessage());
        assertEquals("LOCAL_INVENTORY", openResp.getStockSource());
        assertTrue(openResp.isAllowSale(), "Người dùng vẫn có thể tiếp tục thao tác bán hàng!");
    }
}
