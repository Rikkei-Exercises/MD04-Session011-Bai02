package com.example.pharmacyservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillResponse {
    private double medicineSubtotal; // Tổng tiền thuốc
    private double vatRate;          // % thuế VAT đang áp dụng (pharmacy.vat-rate)
    private double vatAmount;        // Số tiền thuế VAT = tổng tiền thuốc * (% VAT / 100)
    private double totalAmount;      // Tổng tiền thanh toán = tổng tiền thuốc + tiền thuế VAT
    private String formula;          // Công thức tính toán chi tiết
    private List<BillItem> items;    // Chi tiết danh sách thuốc (nếu có)
}
