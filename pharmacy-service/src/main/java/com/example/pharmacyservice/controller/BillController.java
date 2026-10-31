package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.dto.BillRequest;
import com.example.pharmacyservice.dto.BillResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RefreshScope
@RestController
@RequestMapping("/api/v1/bill")
public class BillController {

    /**
     * Tỷ lệ thuế VAT lấy từ Config Server (Git: pharmacy.vat-rate).
     * Khi gọi POST /actuator/refresh, @RefreshScope sẽ làm mới giá trị này tự động.
     */
    @Value("${pharmacy.vat-rate:10.0}")
    private double vatRate;

    /**
     * API tính tiền hóa đơn thuốc theo công thức:
     * Tổng tiền = Tổng tiền thuốc + (Tổng tiền thuốc * % thuế VAT)
     */
    @PostMapping
    public ResponseEntity<BillResponse> calculateBill(@RequestBody(required = false) BillRequest request) {
        if (request == null) {
            request = new BillRequest();
        }

        double subtotal = request.resolveSubtotal();
        double vatAmount = subtotal * (vatRate / 100.0);
        double totalAmount = subtotal + vatAmount;

        String formula = String.format("%.2f (tiền thuốc) + %.2f%% VAT (%.2f) = %.2f (tổng cộng)",
                subtotal, vatRate, vatAmount, totalAmount);

        BillResponse response = BillResponse.builder()
                .medicineSubtotal(subtotal)
                .vatRate(vatRate)
                .vatAmount(vatAmount)
                .totalAmount(totalAmount)
                .formula(formula)
                .items(request.getItems())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint hỗ trợ kiểm tra tỷ lệ VAT hiện tại đang áp dụng
     */
    @GetMapping("/vat-rate")
    public ResponseEntity<Map<String, Object>> getCurrentVatRate() {
        Map<String, Object> result = new HashMap<>();
        result.put("vatRate", vatRate);
        result.put("message", "Tỷ lệ VAT hiện hành: " + vatRate + "%");
        return ResponseEntity.ok(result);
    }
}
