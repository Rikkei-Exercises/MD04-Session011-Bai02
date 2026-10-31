package com.example.pharmacyservice.runner;

import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class BranchInfoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BranchInfoRunner.class);

    @Value("${app.branch-name}")
    private String branchName;

    @Value("${app.hotline}")
    private String hotline;

    @Value("${spring.datasource.url:N/A}")
    private String datasourceUrl;

    private final DataSource dataSource;
    private final MedicineRepository medicineRepository;

    public BranchInfoRunner(DataSource dataSource, MedicineRepository medicineRepository) {
        this.dataSource = dataSource;
        this.medicineRepository = medicineRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        String dbProductName = "Unknown";
        String dbUrl = datasourceUrl;

        try (Connection connection = dataSource.getConnection()) {
            dbProductName = connection.getMetaData().getDatabaseProductName();
            dbUrl = connection.getMetaData().getURL();
        }

        // Khởi tạo một vài dữ liệu mẫu cho kho thuốc để chứng minh Database hoạt động tốt
        if (medicineRepository.count() == 0) {
            medicineRepository.save(Medicine.builder().name("Paracetamol 500mg").quantity(100).unitPrice(2500.0).build());
            medicineRepository.save(Medicine.builder().name("Amoxicillin 500mg").quantity(50).unitPrice(5000.0).build());
            medicineRepository.save(Medicine.builder().name("Vitamin C 1000mg").quantity(200).unitPrice(3000.0).build());
        }

        long totalMedicines = medicineRepository.count();

        System.out.println("======================================================================");
        System.out.println("   PHARMACY-SERVICE ĐÃ KHỞI ĐỘNG VÀ LẤY CẤU HÌNH TỪ CONFIG SERVER");
        System.out.println("======================================================================");
        System.out.println("-> Tên chi nhánh (app.branch-name) : " + branchName);
        System.out.println("-> Đường dây nóng (app.hotline)    : " + hotline);
        System.out.println("-> Datasource URL cấu hình         : " + dbUrl);
        System.out.println("-> Loại cơ sở dữ liệu kết nối      : " + dbProductName);
        System.out.println("-> Trạng thái kết nối Database     : THÀNH CÔNG (Tự động kết nối DB)");
        System.out.println("-> Số lượng loại thuốc trong kho   : " + totalMedicines);
        System.out.println("======================================================================");

        log.info("Khởi động pharmacy-service hoàn tất. Chi nhánh: '{}', Hotline: '{}'", branchName, hotline);
    }
}
