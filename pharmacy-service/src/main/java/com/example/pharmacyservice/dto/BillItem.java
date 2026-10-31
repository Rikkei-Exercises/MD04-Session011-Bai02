package com.example.pharmacyservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillItem {
    private String medicineName;
    private int quantity;
    private double unitPrice;

    public double getItemTotal() {
        return quantity * unitPrice;
    }
}
