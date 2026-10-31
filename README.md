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
