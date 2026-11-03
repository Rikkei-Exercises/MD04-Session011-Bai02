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
1. **Service gọi Kho tổng & Hàm Fallback (`WarehouseClientService.java`)**:
   ```java
   @CircuitBreaker(name = "warehouseCB", fallbackMethod = "checkWarehouseFallback")
   public StockCheckResponse checkWarehouse(String medicineName) {
       // Gọi API Kho tổng (warehouse-service)
       // Nếu kho sập hoặc ném ngoại lệ -> kích hoạt tính toán Circuit Breaker
   }

   /**
    * Hàm fallback: checkWarehouseFallback(Exception e)
    * Khi mạch ngắt hoặc kho lỗi: chuyển sang dùng tồn kho tại chỗ (LOCAL_INVENTORY)
    * Trả về thông báo hướng dẫn cụ thể thay vì ném lỗi 500
    */
   public StockCheckResponse checkWarehouseFallback(Exception e) {
       String medicineName = Optional.ofNullable(currentMedicineContext.get()).orElse("Paracetamol 500mg");
       int localStock = medicineRepository.findByName(medicineName)
               .map(Medicine::getQuantity).orElse(100);

       return StockCheckResponse.builder()
               .medicineName(medicineName)
               .warehouseStock(0)
               .localStock(localStock)
               .availableStock(localStock)
               .inStock(localStock > 0)
               .stockSource("LOCAL_INVENTORY")
               .circuitBreakerState(getCircuitBreakerState())
               .message("Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch")
               .allowSale(localStock > 0)
               .build();
   }
   ```
2. **Controller bán lẻ (`RetailController.java`)**:
   - `GET /api/v1/retail/check-warehouse-stock?medicineName=...`: Bán lẻ kiểm tra tồn kho. Khi kho lỗi/mạch OPEN, luôn trả về HTTP 200 OK kèm dữ liệu tồn kho tại chỗ và thông báo hướng dẫn.
   - `POST /api/v1/retail/sell?medicineName=...&quantity=...`: Bán lẻ thuốc trực tiếp dựa trên tồn kho tại chỗ khi kho tổng sập.
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
2. Hàm `checkWarehouseFallback(Exception e)` trả về chính xác thông báo:
   `"Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch"` và cung cấp số lượng tồn kho tại chỗ (`LOCAL_INVENTORY`).
3. Khi kho tổng sập: Sau các lần gọi lỗi, mạch chuyển từ `CLOSED` sang `OPEN`.
4. Khi mạch `OPEN`: Request tiếp theo được ngắt mạch ngay lập tức (**Fail-fast** < 50ms) và trả về dữ liệu tồn kho cục bộ cho phép tiếp tục bán lẻ thay vì báo lỗi 500.

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
- Khi kho tổng lỗi, hàm `checkWarehouseFallback(Exception e)` lập tức kích hoạt, trả về **HTTP 200 OK**:
```json
{
  "medicineName": "Paracetamol 500mg",
  "warehouseStock": 0,
  "localStock": 100,
  "availableStock": 100,
  "inStock": true,
  "stockSource": "LOCAL_INVENTORY",
  "circuitBreakerState": "CLOSED",
  "message": "Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch",
  "allowSale": true
}
```

#### Bước 4: Kiểm tra trạng thái Circuit Breaker
Sau 4 lần gọi lỗi (tỷ lệ lỗi 100% > 50%), mạch chuyển sang OPEN:
```bash
curl -X GET http://localhost:8081/api/v1/retail/circuit-breaker-status
```
Kết quả: `state = "OPEN"`, `failureRate = 100.0%`.

#### Bước 5: Kiểm tra cơ chế Fail-fast và tiếp tục bán hàng khi mạch OPEN
Gửi request bán lẻ kiểm tra tồn kho lần thứ 5:
```bash
curl -X GET "http://localhost:8081/api/v1/retail/check-warehouse-stock?medicineName=Paracetamol%20500mg"
```
Kết quả trả về **HTTP 200 OK** (thời gian phản hồi `0ms`):
```json
{
  "medicineName": "Paracetamol 500mg",
  "warehouseStock": 0,
  "localStock": 100,
  "availableStock": 100,
  "inStock": true,
  "stockSource": "LOCAL_INVENTORY",
  "circuitBreakerState": "OPEN",
  "message": "Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch",
  "executionTimeMs": 0,
  "failFast": true,
  "allowSale": true
}
```
*Không nhận mã lỗi 500, người dùng có thể tiếp tục bán hàng dựa trên số lượng tồn kho tại chỗ (100 hộp Paracetamol).*

Gửi request bán hàng trực tiếp:
```bash
curl -X POST "http://localhost:8081/api/v1/retail/sell?medicineName=Paracetamol%20500mg&quantity=2"
```
Kết quả trả về:
```json
{
  "status": "SUCCESS",
  "medicineName": "Paracetamol 500mg",
  "requestedQuantity": 2,
  "remainingStock": 98,
  "stockSource": "LOCAL_INVENTORY",
  "stockMessage": "Không thể kết nối kho tổng. Hệ thống sẽ sử dụng dữ liệu tồn kho cục bộ để tiếp tục giao dịch",
  "transactionStatus": "Giao dịch bán lẻ thành công!"
}
```

#### Bước 6: Sau 20 giây (wait-duration-in-open-state)
Mạch tự động chuyển sang `HALF_OPEN`. Khi kho tổng hoạt động lại (`simulate-warehouse?down=false`), các request thành công sẽ đưa mạch trở lại `CLOSED`.

---

## 7. Kiến trúc Event-Driven với Apache Kafka: Producer & Consumer

Hệ thống triển khai mô hình kiến trúc hướng sự kiện (Event-Driven Architecture) giữa 2 Microservices thông qua Apache Kafka:
- **`pharmacy-service` (Producer - Cổng 8081)**: Khi nhân viên nhấn "Thanh toán" hoặc gọi API bán thuốc, hệ thống phát sinh sự kiện `OrderEvent` và dùng `KafkaTemplate` gửi lên Kafka Broker dưới định dạng JSON vào topic `medicine-stock-events`.
  - **Partitioning Challenge**: Sử dụng Message Key là `medicineId` (`String.valueOf(medicineId)`). Kafka dùng thuật toán băm (murmur2) `hash(key) % partitions` đảm bảo mọi đơn hàng của cùng một loại thuốc **luôn được định tuyến vào cùng một Partition**, giữ đúng thứ tự giao dịch (ordering guarantee).
- **`inventory-service` (Consumer - Cổng 8083)**: Lắng nghe topic `medicine-stock-events` thông qua annotation `@KafkaListener` với `group-id: inventory-group`.
  - Đọc thông tin sự kiện `OrderEvent`.
  - Thêm mới đơn hàng vào cơ sở dữ liệu (`orders` table).
  - Thực thi câu lệnh SQL trực tiếp xuống Database:
    ```sql
    UPDATE medicines SET stock = stock - quantity WHERE id = medicineId;
    ```
  - Cấu hình `group-id: inventory-group` đảm bảo tính nhất quán và xử lý song song không trùng lặp (idempotency).

```
[Nhân viên bấm Thanh toán / Gọi API]
                │
                ▼
   ┌──────────────────────────┐
   │     pharmacy-service     │ (Producer - Port 8081)
   │  KafkaTemplate<JSON>     │
   └────────────┬─────────────┘
                │ Topic: medicine-stock-events
                │ Message Key: medicineId (Partitioning)
                ▼
   ┌──────────────────────────┐
   │       KAFKA BROKER       │ (Port 9092)
   │  Topic: medicine-stock   │
   │  - Partition 0           │
   │  - Partition 1           │
   │  - Partition 2           │
   └────────────┬─────────────┘
                │ @KafkaListener(groupId = "inventory-group")
                ▼
   ┌──────────────────────────┐
   │    inventory-service     │ (Consumer - Port 8083)
   │ 1. Thêm mới OrderRecord  │
   │ 2. UPDATE medicines      │
   │    SET stock = stock - q │
   └──────────────────────────┘
```

---

## 8. Chi tiết các thành phần Kafka & Hướng dẫn kiểm thử

### A. Định dạng sự kiện `OrderEvent`
```java
public class OrderEvent implements Serializable {
    private String orderId;    // Mã đơn hàng (VD: ORD-A1B2C3D4)
    private Long medicineId;   // Mã thuốc (dùng làm Message Key)
    private Integer quantity;  // Số lượng bán
    private Long timestamp;    // Thời gian phát sinh đơn hàng
}
```

### B. Màn hình Giao diện Web bán thuốc (Pharmacy POS)
Mở trình duyệt: `http://localhost:8081/`
- Chọn loại thuốc (Paracetamol, Amoxicillin, Vitamin C).
- Nhập số lượng và nhấn nút **"Thanh toán"**.
- Màn hình hiển thị:
  - Thông báo **"Thanh toán thành công!"**
  - Chi tiết đơn hàng và sự kiện Kafka (Topic, Message Key, Partition, Payload JSON).

### C. Kiểm thử qua cURL / REST API

#### 1. Gọi API Thanh toán đơn hàng (`pharmacy-service`):
```bash
curl -X POST "http://localhost:8081/api/v1/orders/checkout" \
  -H "Content-Type: application/json" \
  -d '{
    "medicineId": 1,
    "quantity": 3
  }'
```
Phản hồi:
```json
{
  "status": "SUCCESS",
  "message": "Thanh toán thành công! Sự kiện đơn hàng đã gửi lên Kafka broker.",
  "orderId": "ORD-5D8E9F1A",
  "medicineId": 1,
  "medicineName": "Paracetamol 500mg",
  "quantity": 3,
  "totalAmount": 7500.0,
  "topic": "medicine-stock-events",
  "messageKey": "1",
  "orderEvent": {
    "orderId": "ORD-5D8E9F1A",
    "medicineId": 1,
    "quantity": 3,
    "timestamp": 1730602800000
  }
}
```

#### 2. Kiểm tra tồn kho và đơn hàng tự động cập nhật (`inventory-service`):
```bash
# Xem tồn kho sau khi đã trừ (UPDATE medicines SET stock = stock - quantity)
curl "http://localhost:8083/api/v1/inventory/medicines"

# Xem danh sách đơn hàng được Consumer thêm mới
curl "http://localhost:8083/api/v1/inventory/orders"

# Xem nhật ký xử lý của Consumer
curl "http://localhost:8083/api/v1/inventory/logs"
```

---

## 9. Notification Service - Gửi Hóa Đơn Điện Tử / Email

Khi một sự kiện đơn hàng được tạo, `notification-service` (Port 8084) cùng lắng nghe topic `medicine-stock-events` để xử lý thông báo và gửi hóa đơn điện tử cho khách hàng.

### A. Cơ chế Broadcast qua Multi Consumer Groups
- `inventory-service` sử dụng `group-id: inventory-group`.
- `notification-service` sử dụng `group-id: notification-group`.
Do hai service thuộc **hai Consumer Group khác nhau**, Kafka sẽ **nhân bản và gửi đồng thời** sự kiện `OrderEvent` đến cả hai service một cách độc lập mà không bị tranh chấp partition:
1. `inventory-service` nhận tin để trừ kho và thêm đơn hàng.
2. `notification-service` nhận tin để phát sinh hóa đơn và gửi email cho khách.

```
                  ┌───────────────────────────┐
                  │     pharmacy-service      │ (Producer - Port 8081)
                  └─────────────┬─────────────┘
                                │ Topic: medicine-stock-events
                                ▼
                  ┌───────────────────────────┐
                  │       KAFKA BROKER        │
                  └──────┬─────────────┬──────┘
                         │             │
        (group: inventory-group)     (group: notification-group)
                         │             │
                         ▼             ▼
   ┌───────────────────────────┐     ┌───────────────────────────┐
   │     inventory-service     │     │   notification-service    │ (Port 8084)
   │  UPDATE medicines         │     │  - Log thông báo          │
   │  SET stock = stock - q    │     │  - Gửi hóa đơn điện tử    │
   │  INSERT INTO orders       │     │    qua Email cho khách    │
   └───────────────────────────┘     └───────────────────────────┘
```

### B. Yêu cầu log & Email
Khi nhận sự kiện, `notification-service` in ra log theo đúng định dạng yêu cầu:
```
>>> THÔNG BÁO: Hóa đơn cho đơn hàng [ORD-XXXX] đã được gửi tới khách hàng <<<
```
Đồng thời kích hoạt dịch vụ `EmailService` để tạo nội dung hóa đơn điện tử chi tiết và gửi tới email của khách hàng.

### C. Kiểm tra qua REST API (`notification-service`):
```bash
# Xem danh sách hóa đơn điện tử đã gửi email
curl "http://localhost:8084/api/v1/notifications"

# Xem danh sách log thông báo
curl "http://localhost:8084/api/v1/notifications/logs"
```





---

## 10. Bài thực hành: Cấu hình Redis Cache Provider, @Cacheable & @CacheEvict

### A. Bối cảnh & Yêu cầu kỹ thuật
- **Bối cảnh**: Danh mục thuốc của hiệu thuốc rất lớn (hàng nghìn loại). Mỗi khi khách hàng tìm kiếm hoặc quầy bán lẻ lấy thông tin thuốc, việc truy vấn trực tiếp liên tục vào cơ sở dữ liệu làm hệ thống bị chậm. Đồng thời, thông tin giá thuốc có thể thay đổi. Nếu Cache lưu quá lâu mà không có cơ chế vô hiệu hóa, khách hàng sẽ thấy giá cũ.
- **Giải pháp**:
  - Tích hợp **Redis làm Cache Provider**, cấu hình TTL là **60 phút**.
  - Áp dụng `@Cacheable(value = "medicines", key = "#id")` tại hàm lấy chi tiết thuốc.
  - Sử dụng `@CacheEvict(value = "medicines", key = "#medicine.id")` tại hàm cập nhật thông tin thuốc. Khi một loại thuốc được sửa giá, Cache cũ bị xóa ngay lập tức để lần truy vấn sau hệ thống sẽ lấy giá mới nhất từ DB.

---

### B. Cấu hình chi tiết

#### 1. Khai báo dependencies (`pharmacy-service/build.gradle`):
```groovy
implementation 'org.springframework.boot:spring-boot-starter-cache'
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

#### 2. Cấu hình Redis Cache Provider (`application.properties`):
```properties
# Bật in câu lệnh SQL để quan sát
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Cấu hình Redis làm Cache Provider (TTL = 60 phút = 3600000ms)
spring.cache.type=redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.cache.redis.time-to-live=3600000
spring.cache.redis.cache-null-values=false
```

#### 3. Cấu hình Redis JSON Serialization (`RedisConfig.java`):
Dữ liệu thuốc được lưu trữ trên Redis dưới dạng JSON chuẩn xác, TTL = 60 phút:
```java
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheConfiguration redisCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.json()));
    }
}
```

#### 4. Áp dụng `@Cacheable` & `@CacheEvict` tại Service (`MedicineService.java`):
```java
@Service
public class MedicineService {

    private static final Logger log = LoggerFactory.getLogger(MedicineService.class);
    private final MedicineRepository medicineRepository;

    public MedicineService(MedicineRepository medicineRepository) {
        this.medicineRepository = medicineRepository;
    }

    /**
     * Lấy chi tiết thuốc theo ID có áp dụng Redis Cache.
     * Cache Hit: Trả về kết quả từ Redis < 10ms, không chạy SQL.
     */
    @Cacheable(value = "medicines", key = "#id")
    public Medicine getMedicineById(Long id) {
        log.info("===> [DATABASE QUERY] Đang truy vấn Database để lấy thông tin thuốc với ID = {}", id);
        return medicineRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + id));
    }

    /**
     * Cập nhật thông tin thuốc (sửa giá).
     * @CacheEvict xóa ngay lập tức key medicines::{id} khỏi Redis.
     */
    @CacheEvict(value = "medicines", key = "#medicine.id")
    public Medicine updateMedicine(Medicine medicine) {
        log.info("===> [CACHE EVICT] Đã kích hoạt @CacheEvict xóa cache 'medicines' key = {} do thuốc được cập nhật", medicine.getId());
        Medicine existing = medicineRepository.findById(medicine.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + medicine.getId()));

        if (medicine.getName() != null && !medicine.getName().isBlank()) {
            existing.setName(medicine.getName());
        }
        if (medicine.getQuantity() != null) {
            existing.setQuantity(medicine.getQuantity());
        }
        if (medicine.getUnitPrice() != null) {
            existing.setUnitPrice(medicine.getUnitPrice());
        }

        return medicineRepository.save(existing);
    }
}
```

---

### C. Hướng dẫn kiểm thử 4 bước thực hành

#### Cách 1: Chạy tự động qua JUnit Test
```powershell
cd pharmacy-service
.\gradlew test --tests "com.example.pharmacyservice.MedicineServiceCacheTest"
```
Bộ test tự động kiểm chứng toàn bộ quy trình:
1. `testRedisCacheConfiguration`: Cấu hình TTL 60 phút, không cache null, key text, value JSON.
2. `testFirstCallQueriesDatabase`: Gọi lần 1 -> Cache Miss -> Chạy truy vấn DB (gọi 1 lần).
3. `testSecondCallHitsCacheAndSkipsDatabase`: Gọi lần 2 -> Cache Hit -> Bỏ qua DB, thời gian phản hồi < 10ms.
4. `testCacheEvictOnUpdateMedicine`: Bước 3 cập nhật giá thuốc -> `@CacheEvict` xóa key trong cache -> Bước 4 gọi lại thuốc ID=1, hệ thống truy vấn lại Database để nạp giá mới nhất!

#### Cách 2: Thực hành trực tiếp qua REST API (curl / Postman)

##### Bước 1: Gọi API lấy thuốc ID=1 (Lần đầu - Cache Miss)
```bash
curl -i http://localhost:8081/api/pharmacy/medicines/1
```
- **Log Console**: Thấy câu lệnh SQL truy vấn và log của hệ thống:
  ```text
  ===> [DATABASE QUERY] Đang truy vấn Database để lấy thông tin thuốc với ID = 1
  Hibernate: select m1_0.id,m1_0.name,m1_0.quantity,m1_0.unit_price from medicines m1_0 where m1_0.id=?
  ```
- **Tốc độ phản hồi**: Chậm hơn do phải truy vấn Database. Kết quả được lưu vào Redis với key `medicines::1`.

##### Bước 2: Gọi lại API lấy thuốc ID=1 lần 2 (Cache Hit)
```bash
curl -i http://localhost:8081/api/pharmacy/medicines/1
```
- **Log Console**: **Hoàn toàn KHÔNG hiện câu lệnh SQL**, không có log `[DATABASE QUERY]`.
- **Tốc độ phản hồi**: Cực nhanh (**< 10ms**), Header `X-Response-Time-Millis: 2` ms.
- Dữ liệu đang được lấy trực tiếp từ Redis Cache.

##### Bước 3 (Chốt hạ): Gọi API cập nhật giá thuốc ID=1 (Sửa giá lên 5000)
Gửi request `PUT http://localhost:8081/api/pharmacy/medicines/1`:
```bash
curl -X PUT http://localhost:8081/api/pharmacy/medicines/1 \
  -H "Content-Type: application/json" \
  -d '{
    "unitPrice": 5000.0
  }'
```
- **Log Console**:
  ```text
  ===> [CACHE EVICT] Đã kích hoạt @CacheEvict xóa cache 'medicines' key = 1 do thuốc được cập nhật
  Hibernate: update medicines set name=?,quantity=?,unit_price=? where id=?
  ```
- **Kiểm tra Redis**: Mở Redis Desktop Manager hoặc mở terminal redis-cli gõ:
  ```bash
  KEYS *
  ```
  -> Key `medicines::1` đã **biến mất hoàn toàn** khỏi Redis do `@CacheEvict` kích hoạt!

##### Bước 4: Gọi lại API lấy thuốc ID=1 sau khi sửa giá
```bash
curl -i http://localhost:8081/api/pharmacy/medicines/1
```
- **Log Console**: Xuất hiện lại log và câu lệnh SQL:
  ```text
  ===> [DATABASE QUERY] Đang truy vấn Database để lấy thông tin thuốc với ID = 1
  Hibernate: select m1_0.id,m1_0.name,m1_0.quantity,m1_0.unit_price from medicines m1_0 where m1_0.id=?
  ```
- **Kết quả trả về**: Chứa giá mới nhất `5000.0`:
  ```json
  {
    "id": 1,
    "name": "Paracetamol 500mg",
    "quantity": 100,
    "unitPrice": 5000.0
  }
  ```
- Hệ thống đã nhận ra Cache cũ đã bị xóa, tự động nạp lại dữ liệu mới nhất từ Database vào Redis Cache.

1. **Khởi động Redis Server**:
   ```powershell
   # Nếu dùng Docker:
   docker run -d --name redis-server -p 6379:6379 redis:alpine
   ```

2. **Khởi động Pharmacy Service**:
   ```powershell
   cd pharmacy-service
   .\gradlew bootRun
   ```

3. **Gọi lần 1 (Cache Miss)**:
   ```bash
   curl -i http://localhost:8081/api/pharmacy/medicines/1
   ```
   - **Log Server**:
     ```text
     ===> [DATABASE QUERY] Đang truy vấn Database để lấy thông tin thuốc với ID = 1
     Hibernate: select m1_0.id,m1_0.name,m1_0.quantity,m1_0.unit_price from medicines m1_0 where m1_0.id=?
     ```
   - **Response**: Trả về dữ liệu `Paracetamol 500mg`. Dữ liệu lập tức được lưu vào Redis với key `medicines::1`.

4. **Gọi lần 2 (Cache Hit)**:
   ```bash
   curl -i http://localhost:8081/api/pharmacy/medicines/1
   ```
   - **Log Server**: **HOÀN TOÀN KHÔNG CÓ CÂU LỆNH SQL NÀO**, không có log `[DATABASE QUERY]`.
   - **Header**: `X-Response-Time-Millis: 2` (phản hồi siêu tốc `< 10ms`).
   - Dữ liệu được đọc trực tiếp từ Redis Cache, Database hoàn toàn không bị chạm tới!
