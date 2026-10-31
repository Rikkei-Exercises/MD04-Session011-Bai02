# Session 09 - Spring Cloud Config với Pharmacy Service

Dự án này triển khai kiến trúc Spring Cloud Config Server & Client tập trung toàn bộ cấu hình vào Git Repository cho dịch vụ `pharmacy-service`.

---

## 1. Cấu trúc thư mục

Toàn bộ bài thực hành được tổ chức bên trong thư mục `Session-09`:

```
Session-09/
├── pharmacy-config-repo/               # Git Repository lưu file cấu hình
│   ├── .git/
│   └── pharmacy-service.properties     # Chứa datasource, app.branch-name, app.hotline
├── config-server/                      # Spring Cloud Config Server (Port 8888)
│   ├── src/main/
│   │   ├── java/com/example/configserver/ConfigServerApplication.java
│   │   └── resources/application.properties
│   └── build.gradle
└── pharmacy-service/                   # Pharmacy Service Client (Port 8081)
    ├── src/main/
    │   ├── java/com/example/pharmacyservice/
    │   │   ├── controller/PharmacyController.java
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
```

### B. Config Server (`config-server`): Port 8888
- **`application.properties`**:
  ```properties
  server.port=8888
  spring.application.name=config-server
  spring.cloud.config.server.git.uri=file:///D:/Rikei/Module_04/Session-09/pharmacy-config-repo
  spring.cloud.config.server.git.default-label=main
  ```
- **`ConfigServerApplication.java`**: Kích hoạt với `@EnableConfigServer`.

### C. Client (`pharmacy-service`):
- **`application.properties`**:
  ```properties
  spring.application.name=pharmacy-service
  spring.config.import=configserver:http://localhost:8888
  ```
- **`BranchInfoRunner.java`**:
  Sử dụng `@Value("${app.branch-name}")` và `@Value("${app.hotline}")` cùng `DataSource` để tự động kết nối cơ sở dữ liệu và in thông tin chi nhánh ra console khi ứng dụng khởi động.
- **`PharmacyController.java`**:
  Cung cấp endpoint:
  - `GET /api/pharmacy/branch-info`: Trả về thông tin chi nhánh, hotline và datasource.
  - `GET /api/pharmacy/medicines`: Danh sách các loại thuốc trong kho.

---

## 3. Hướng dẫn chạy thử nghiệm

### Bước 1: Khởi động Config Server
Mở terminal tại thư mục `Session-09/config-server`:
```powershell
.\gradlew bootRun
```
Hoặc chạy file JAR:
```powershell
java -jar build/libs/config-server-0.0.1-SNAPSHOT.jar
```
*Kiểm tra cấu hình:* Truy cập trình duyệt hoặc curl:
`http://localhost:8888/pharmacy-service/default`

### Bước 2: Khởi động Pharmacy Service
Mở terminal khác tại thư mục `Session-09/pharmacy-service`:
```powershell
.\gradlew bootRun
```
Hoặc chạy file JAR:
```powershell
java -jar build/libs/pharmacy-service-0.0.1-SNAPSHOT.jar
```

### Bước 3: Xem kết quả console
Khi khởi động, `pharmacy-service` sẽ tự động lấy thông tin từ Git qua Config Server và in ra:
```text
======================================================================
   PHARMACY-SERVICE ĐÃ KHỞI ĐỘNG VÀ LẤY CẤU HÌNH TỪ CONFIG SERVER
======================================================================
-> Tên chi nhánh (app.branch-name) : Nha Thuoc So 1
-> Đường dây nóng (app.hotline)    : 1900xxxx
-> Datasource URL cấu hình         : jdbc:h2:mem:pharmacy_db
-> Loại cơ sở dữ liệu kết nối      : H2
-> Trạng thái kết nối Database     : THÀNH CÔNG (Tự động kết nối DB)
-> Số lượng loại thuốc trong kho   : 3
======================================================================
```
Truy cập endpoint API kiểm tra:
`http://localhost:8081/api/pharmacy/branch-info`
