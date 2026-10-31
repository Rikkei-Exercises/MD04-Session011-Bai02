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
public class BillRequest {
    // Có thể truyền trực tiếp tổng tiền thuốc
    private Double subtotal;

    // Hoặc truyền danh sách chi tiết các loại thuốc trong đơn
    private List<BillItem> items;

    public double resolveSubtotal() {
        if (subtotal != null && subtotal > 0) {
            return subtotal;
        }
        if (items != null && !items.isEmpty()) {
            return items.stream()
                    .mapToDouble(BillItem::getItemTotal)
                    .sum();
        }
        return 0.0;
    }
}
