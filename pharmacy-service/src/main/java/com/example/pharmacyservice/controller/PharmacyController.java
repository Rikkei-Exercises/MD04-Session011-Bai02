package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RefreshScope
@RestController
@RequestMapping("/api/pharmacy")
public class PharmacyController {

    @Value("${app.branch-name}")
    private String branchName;

    @Value("${app.hotline}")
    private String hotline;

    @Value("${spring.datasource.url:N/A}")
    private String datasourceUrl;

    private final MedicineRepository medicineRepository;

    public PharmacyController(MedicineRepository medicineRepository) {
        this.medicineRepository = medicineRepository;
    }

    @GetMapping("/branch-info")
    public ResponseEntity<Map<String, Object>> getBranchInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("branchName", branchName);
        response.put("hotline", hotline);
        response.put("datasourceUrl", datasourceUrl);
        response.put("status", "SUCCESS");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/medicines")
    public ResponseEntity<List<Medicine>> getAllMedicines() {
        return ResponseEntity.ok(medicineRepository.findAll());
    }

    @PostMapping("/medicines")
    public ResponseEntity<Medicine> addMedicine(@RequestBody Medicine medicine) {
        return ResponseEntity.ok(medicineRepository.save(medicine));
    }
}
