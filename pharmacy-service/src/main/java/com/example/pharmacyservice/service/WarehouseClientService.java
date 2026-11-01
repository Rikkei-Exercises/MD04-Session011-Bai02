package com.example.pharmacyservice.service;

import com.example.pharmacyservice.dto.StockCheckResponse;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class WarehouseClientService {

    private final RestTemplate restTemplate;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MedicineRepository medicineRepository;

    @Value("${warehouse.service.url:http://localhost:8082}")
    private String warehouseUrl;

    private volatile boolean simulateDown = false;
    private volatile long simulateDelayMs = 0;

    // Ngữ cảnh tên thuốc đang kiểm tra trong thread hiện tại
    private final ThreadLocal<String> currentMedicineContext = new ThreadLocal<>();

    public WarehouseClientService(CircuitBreakerRegistry circuitBreakerRegistry,
                                  MedicineRepository medicineRepository) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restTemplate = new RestTemplate(factory);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.medicineRepository = medicineRepository;
    }

    /**
     * Hàm gọi sang Kho tổng (warehouse-service) để kiểm tra tồn kho.
     * Đăng ký @CircuitBreaker với fallbackMethod = "checkWarehouseFallback".
     */
    @CircuitBreaker(name = "warehouseCB", fallbackMethod = "checkWarehouseFallback")
    public StockCheckResponse checkWarehouse(String medicineName) {
        currentMedicineContext.set(medicineName);
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
            int localStock = medicineRepository.findByName(medicineName)
                    .map(Medicine::getQuantity)
                    .orElse(stock);

            currentMedicineContext.remove();
            return StockCheckResponse.builder()
                    .medicineName(medicineName)
                    .warehouseStock(stock)
                    .localStock(localStock)
                    .availableStock(stock)
                    .inStock(stock > 0)
                    .stockSource("WAREHOUSE")
                    .status("SUCCESS")
                    .circuitBreakerState(state)
                    .message("Kiểm tra tồn kho thành công từ Warehouse Service.")
                    .executionTimeMs(duration)
                    .failFast(false)
                    .allowSale(stock > 0)
                    .build();
        } catch (ResourceAccessException e) {
            log.error("!! [LỖI KẾT NỐI] Không thể kết nối tới Warehouse Service tại {}: {}", url, e.getMessage());
            throw new RuntimeException("Không thể kết nối đến Kho tổng (Connection refused/timeout): " + e.getMessage(), e);
        }
    }

    /**
     * Hàm gọi kho tổng mặc định không tham số
     */
    @CircuitBreaker(name = "warehouseCB", fallbackMethod = "checkWarehouseFallback")
    public StockCheckResponse checkWarehouse() {
        return checkWarehouse("Paracetamol 500mg");
    }

    public StockCheckResponse checkStock(String medicineName) {
        return checkWarehouse(medicineName);
    }

    /**
     * Hàm fallback theo đúng yêu cầu kỹ thuật:
     * checkWarehouseFallback(Exception e)
     * Trả về thông báo: "Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch"
     */
    public StockCheckResponse checkWarehouseFallback(Exception e) {
        String medicineName = Optional.ofNullable(currentMedicineContext.get()).orElse("Paracetamol 500mg");
        currentMedicineContext.remove();

        String state = getCircuitBreakerState();
        boolean isFailFast = e instanceof CallNotPermittedException;

        log.warn("<- [FALLBACK KÍCH HOẠT] Thuốc: '{}' | Mạch: {} | Ngoại lệ: {} | Tự động chuyển sang tồn kho tại chỗ",
                medicineName, state, e.getClass().getSimpleName());

        // Lấy số lượng tồn kho tại chỗ từ Database cục bộ (MedicineRepository)
        int localStock = medicineRepository.findByName(medicineName)
                .or(() -> medicineRepository.findByNameIgnoreCase(medicineName))
                .or(() -> medicineRepository.findFirstByNameContainingIgnoreCase(medicineName))
                .map(Medicine::getQuantity)
                .orElse(100);

        String message = "Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch";

        return StockCheckResponse.builder()
                .medicineName(medicineName)
                .warehouseStock(0)
                .localStock(localStock)
                .availableStock(localStock)
                .inStock(localStock > 0)
                .stockSource("LOCAL_INVENTORY")
                .status("FALLBACK_LOCAL_ACTIVE")
                .circuitBreakerState(state)
                .message(message)
                .executionTimeMs(0)
                .failFast(isFailFast)
                .allowSale(localStock > 0)
                .build();
    }

    public String getCircuitBreakerState() {
        try {
            return circuitBreakerRegistry.circuitBreaker("warehouseCB").getState().name();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

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

    public MedicineRepository getMedicineRepository() {
        return medicineRepository;
    }
}
