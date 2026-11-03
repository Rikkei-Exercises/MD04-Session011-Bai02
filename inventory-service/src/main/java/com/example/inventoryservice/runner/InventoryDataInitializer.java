package com.example.inventoryservice.runner;

import com.example.inventoryservice.model.Medicine;
import com.example.inventoryservice.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryDataInitializer implements CommandLineRunner {

    private final MedicineRepository medicineRepository;

    @Override
    public void run(String... args) {
        if (medicineRepository.count() == 0) {
            medicineRepository.save(Medicine.builder().name("Paracetamol 500mg").stock(100).unitPrice(2500.0).build());
            medicineRepository.save(Medicine.builder().name("Amoxicillin 500mg").stock(50).unitPrice(5000.0).build());
            medicineRepository.save(Medicine.builder().name("Vitamin C 1000mg").stock(200).unitPrice(3000.0).build());
            log.info("Khởi tạo 3 loại thuốc mẫu vào bảng medicines của inventory-service thành công!");
        }
    }
}
