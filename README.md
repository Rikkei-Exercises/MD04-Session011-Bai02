# Session 09 - Spring Cloud Config với Pharmacy Service & Dynamic Refresh (@RefreshScope)

Dự án này triển khai kiến trúc Spring Cloud Config Server & Client tập trung toàn bộ cấu hình vào Git Repository cho dịch vụ `pharmacy-service`, hỗ trợ cập nhật cấu hình động không cần khởi động lại máy chủ thông qua Spring Boot Actuator (`@RefreshScope`).

---

## 1. Cấu trúc thư mục

Toàn bộ bài thực hành được tổ chức bên trong thư mục `Session-09`:

```
Session-09/
├── pharmacy-config-repo/               # Git Repository lưu file cấu hình
│   ├── .git/
│   └── pharmacy-service.properties     # Chứa datasource, app.branch-name, app.hotline, pharmacy.vat-rate
├── config-server/                      # Spring Cloud Config Server (Port 8888)
│   ├── src/main/
│   │   ├── java/com/example/configserver/ConfigServerApplication.java
│   │   └── resources/application.properties
│   └── build.gradle
└── pharmacy-service/                   # Pharmacy Service Client (Port 8081)
    ├── src/main/
    │   ├── java/com/example/pharmacyservice/
    │   │   ├── controller/
    │   │   │   ├── BillController.java          # @RefreshScope: Tính tiền hóa đơn thuốc theo VAT
    │   │   │   └── PharmacyController.java
    │   │   ├── dto/
    │   │   │   ├── BillItem.java
    │   │   │   ├── BillRequest.java
    │   │   │   └── BillResponse.java
    │   │   ├── model/Medicine.java
    │   │   ├── repository/MedicineRepository.java
    │   │   ├── runner/BranchInfoRunner.java
    │   │   └── PharmacyServiceApplication.java
    │   └── resources/application.properties
    └── build.gradle
```

---

## 2. Chi tiết cấu hình các thành phần

### A. Git Repository: `pharmacy-config-repo/pharmacy-service.properties`
File cấu hình tập trung chứa các thông số:
```properties
# Server Port
server.port=8081

# Actuator Configuration
management.endpoints.web.exposure.include=*

# Database Configuration
spring.datasource.url=jdbc:h2:mem:pharmacy_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Branch Information
app.branch-name=Nha Thuoc So 1
app.hotline=1900xxxx

# VAT Configuration
pharmacy.vat-rate=8
```

### B. Config Server (`config-server`): Port 8888
- **`application.properties`**:
  ```properties
  server.port=8888
  spring.application.name=config-server
  spring.cloud.config.server.git.uri=file:///D:/Rikei/Module_04/Session-09/pharmacy-config-repo
  spring.cloud.config.server.git.default-label=main
  ```

### C. Client (`pharmacy-service`):
- **`build.gradle`**: Có chứa `org.springframework.boot:spring-boot-starter-actuator` và `org.springframework.cloud:spring-cloud-starter-config`.
- **`BillController.java`**:
  ```java
  @RefreshScope
  @RestController
  @RequestMapping("/api/v1/bill")
  public class BillController {

      @Value("${pharmacy.vat-rate:10.0}")
      private double vatRate;

      @PostMapping
      public ResponseEntity<BillResponse> calculateBill(@RequestBody(required = false) BillRequest request) {
          double subtotal = request.resolveSubtotal();
          double vatAmount = subtotal * (vatRate / 100.0);
          double totalAmount = subtotal + vatAmount;
          // ...
      }
  }
  ```

---

## 3. Hướng dẫn kiểm thử Dynamic Refresh (@RefreshScope)

### Bước 1: Khởi động Config Server và Pharmacy Service
- Config Server: `cd config-server; .\gradlew bootRun` (Port 8888)
- Pharmacy Service: `cd pharmacy-service; .\gradlew bootRun` (Port 8081)

### Bước 2: Gọi API tính tiền khi VAT ban đầu (10%)
Gửi request `POST http://localhost:8081/api/v1/bill`:
```json
{
  "subtotal": 100000,
  "items": [
    { "medicineName": "Paracetamol 500mg", "quantity": 2, "unitPrice": 25000 },
    { "medicineName": "Amoxicillin 500mg", "quantity": 1, "unitPrice": 50000 }
  ]
}
```
Kết quả trả về:
```json
{
  "medicineSubtotal": 100000.0,
  "vatRate": 10.0,
  "vatAmount": 10000.0,
  "totalAmount": 110000.0,
  "formula": "100000.00 (tiền thuốc) + 10.00% VAT (10000.00) = 110000.00 (tổng cộng)"
}
```

### Bước 3: Đổi thuế VAT trên Git (Không tắt Server)
Sửa trong `pharmacy-config-repo/pharmacy-service.properties`:
```properties
pharmacy.vat-rate=8
```
Commit lên Git:
```bash
git add .
git commit -m "Update pharmacy.vat-rate to 8%"
```

### Bước 4: Gọi Actuator Refresh
Gửi request `POST http://localhost:8081/actuator/refresh` (Header `Content-Type: application/json`):
```bash
curl -X POST http://localhost:8081/actuator/refresh -H "Content-Type: application/json" -d "{}"
```
Response:
```json
[
  "config.client.version",
  "pharmacy.vat-rate"
]
```

### Bước 5: Gọi lại API tính tiền kiểm tra kết quả ngay lập tức
Gửi lại request `POST http://localhost:8081/api/v1/bill`:
```json
{
  "medicineSubtotal": 100000.0,
  "vatRate": 8.0,
  "vatAmount": 8000.0,
  "totalAmount": 108000.0,
  "formula": "100000.00 (tiền thuốc) + 8.00% VAT (8000.00) = 108000.00 (tổng cộng)"
}
```
*Tỷ lệ thuế mới (8%) đã được áp dụng tức thì mà không cần khởi động lại ứng dụng.*

---

## 4. Bài 2: Ngắt mạch tự động Resilience4j Circuit Breaker (@CircuitBreaker)

### A. Bối cảnh & Yêu cầu
- Khi bán lẻ tại `pharmacy-service`, hệ thống gọi API sang `warehouse-service` (Kho tổng) để kiểm tra lượng tồn kho.
- Nếu kho tổng phản hồi chậm hoặc bị sập:
  - Máy tính tiền bán lẻ có nguy cơ bị treo nghẽn nếu tiếp tục chờ đợi lâu.
  - Sử dụng **Resilience4j Circuit Breaker** để tự ngắt mạch chuyển sang `OPEN`, kích hoạt cơ chế trả về lỗi nhanh (**Fail-fast**) gần như tức thì (~0ms) nhằm bảo vệ quầy thu ngân.
- **Yêu cầu kỹ thuật**:
  - Cấu hình Resilience4j trên Git (`pharmacy-config-repo/pharmacy-service.properties`):
    - `failure-rate-threshold=50`
    - `wait-duration-in-open-state=20s`
  - Đánh dấu `@CircuitBreaker(name = "warehouseCB")` cho hàm gọi kho tổng.

### B. Cấu hình Resilience4j trên Git (`pharmacy-config-repo/pharmacy-service.properties`)
```properties
# Resilience4j Circuit Breaker Configuration (warehouseCB)
resilience4j.circuitbreaker.instances.warehouseCB.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.warehouseCB.wait-duration-in-open-state=20s
resilience4j.circuitbreaker.instances.warehouseCB.sliding-window-type=COUNT_BASED
resilience4j.circuitbreaker.instances.warehouseCB.sliding-window-size=6
resilience4j.circuitbreaker.instances.warehouseCB.minimum-number-of-calls=4
resilience4j.circuitbreaker.instances.warehouseCB.permitted-number-of-calls-in-half-open-state=2
resilience4j.circuitbreaker.instances.warehouseCB.automatic-transition-from-open-to-half-open-enabled=true
resilience4j.circuitbreaker.instances.warehouseCB.register-health-indicator=true
```

### C. Triển khai Code trong `pharmacy-service`
1. **Service gọi Kho tổng (`WarehouseClientService.java`)**:
   ```java
   @CircuitBreaker(name = "warehouseCB", fallbackMethod = "checkStockFallback")
   public StockCheckResponse checkStock(String medicineName) {
       // Gọi API Kho tổng (warehouse-service)
       // Nếu kho sập hoặc ném ngoại lệ -> kích hoạt tính toán Circuit Breaker
   }

   public StockCheckResponse checkStockFallback(String medicineName, Throwable throwable) {
       boolean isFailFast = throwable instanceof CallNotPermittedException;
       // Khi mạch OPEN -> throwable là CallNotPermittedException (Fail-fast tức thì ~0ms)
       // ...
   }
   ```
2. **Controller bán lẻ (`RetailController.java`)**:
   - `GET /api/v1/retail/check-warehouse-stock?medicineName=...`: Bán lẻ kiểm tra tồn kho từ kho tổng.
   - `POST /api/v1/retail/simulate-warehouse?down=true`: Giả lập kho tổng bị sập để kiểm thử.
   - `GET /api/v1/retail/circuit-breaker-status`: Kiểm tra trạng thái thời gian thực của Circuit Breaker (`CLOSED`, `OPEN`, `HALF_OPEN`).
   - `POST /api/v1/retail/circuit-breaker/reset`: Reset Circuit Breaker về `CLOSED`.

---

## 5. Hướng dẫn kiểm thử Circuit Breaker & Fail-Fast

### Cách 1: Chạy tự động qua JUnit Test
Chạy lệnh kiểm thử đã được tích hợp sẵn:
```powershell
cd pharmacy-service
.\gradlew test --tests "com.example.pharmacyservice.WarehouseCircuitBreakerTest"
```
Bộ kiểm thử sẽ tự động xác minh:
1. Thông số cấu hình trên Git: `failureRateThreshold = 50%`, `waitDurationInOpenState = 20s`.
2. Khi kho tổng sập: Sau các lần gọi lỗi, mạch chuyển từ `CLOSED` sang `OPEN`.
3. Khi mạch `OPEN`: Request tiếp theo được ngắt mạch ngay lập tức (**Fail-fast**), thời gian xử lý < 50ms, bảo vệ hệ thống bán lẻ.

### Cách 2: Kiểm thử trực tiếp qua REST API (curl / Postman)

#### Bước 1: Xem trạng thái ban đầu của mạch
```bash
curl -X GET http://localhost:8081/api/v1/retail/circuit-breaker-status
```
Kết quả: `state = "CLOSED"`.

#### Bước 2: Giả lập kho tổng bị sập
```bash
curl -X POST "http://localhost:8081/api/v1/retail/simulate-warehouse?down=true"
```

#### Bước 3: Gọi API kiểm tra tồn kho liên tiếp 4 lần
```bash
curl -X GET "http://localhost:8081/api/v1/retail/check-warehouse-stock?medicineName=Paracetamol%20500mg"
```
- Trong 4 lần gọi đầu tiên, dịch vụ phát hiện lỗi từ kho tổng và ghi nhận vào cửa sổ tính toán lỗi. Tỷ lệ lỗi đạt `100% > 50%`.

#### Bước 4: Kiểm tra trạng thái Circuit Breaker
```bash
curl -X GET http://localhost:8081/api/v1/retail/circuit-breaker-status
```
Kết quả: `state = "OPEN"`, `failureRate = 100.0%`.

#### Bước 5: Kiểm tra cơ chế Fail-fast khi mạch OPEN
Gửi request bán lẻ kiểm tra tồn kho lần thứ 5:
```bash
curl -X GET "http://localhost:8081/api/v1/retail/check-warehouse-stock?medicineName=Paracetamol%20500mg"
```
Kết quả trả về HTTP 503 với JSON:
```json
{
  "medicineName": "Paracetamol 500mg",
  "warehouseStock": 0,
  "inStock": false,
  "status": "FAIL_FAST_BLOCKED",
  "circuitBreakerState": "OPEN",
  "message": "MẠCH ĐANG NGẮT (OPEN): Kho tổng bị sự cố! Resilience4j tự động ngắt kết nối và trả về lỗi nhanh (Fail-Fast) để chống treo máy tính tiền.",
  "executionTimeMs": 0,
  "failFast": true
}
```
*Thời gian phản hồi là `0ms` (Fail-Fast), không hề mất thời gian chờ đợi hoặc treo giao dịch bán lẻ.*

#### Bước 6: Sau 20 giây (wait-duration-in-open-state)
Mạch tự động chuyển sang `HALF_OPEN`. Khi kho tổng hoạt động lại (`simulate-warehouse?down=false`), các request thành công sẽ đưa mạch trở lại `CLOSED`.

