package com.example.pharmacyservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockCheckResponse {
    private String medicineName;
    private int warehouseStock;
    private int localStock;
    private int availableStock;
    private boolean inStock;
    private String stockSource; // "WAREHOUSE", "LOCAL_INVENTORY"
    private String status;
    private String circuitBreakerState;
    private String message;
    private long executionTimeMs;
    private boolean failFast;
    private boolean allowSale;
}
