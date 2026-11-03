package com.example.pharmacyservice.service;

import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class MedicineService {

    private static final Logger log = LoggerFactory.getLogger(MedicineService.class);

    private final MedicineRepository medicineRepository;

    public MedicineService(MedicineRepository medicineRepository) {
        this.medicineRepository = medicineRepository;
    }

    /**
     * Lấy thông tin chi tiết thuốc theo ID có áp dụng Redis Cache.
     *
     * Cơ chế hoạt động:
     * - Lần 1: Kiểm tra trong Redis cache 'medicines' với key = #id.
     *   Chưa có -> Spring thực thi phương thức bên dưới, truy vấn trực tiếp vào Database (hiện log SQL).
     *   Sau đó tự động lưu kết quả đối tượng thuốc vào Redis dưới dạng JSON/byte array.
     * - Lần 2+: Với cùng #id, dữ liệu đã tồn tại trong Redis.
     *   Spring trả về ngay kết quả từ Redis Cache mà KHÔNG thực thi phương thức bên dưới
     *   (không truy vấn Database, không in log SQL, thời gian phản hồi < 10ms).
     */
    @Cacheable(value = "medicines", key = "#id")
    public Medicine getMedicineById(Long id) {
        log.info("===> [DATABASE QUERY] Đang truy vấn Database để lấy thông tin thuốc với ID = {}", id);
        return medicineRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + id));
    }

    /**
     * Cập nhật thông tin thuốc (giá thuốc, số lượng...).
     * 
     * Sử dụng @CacheEvict(value = "medicines", key = "#medicine.id"):
     * Khi cập nhật thuốc thành công, Cache cũ trong Redis sẽ bị xóa ngay lập tức (evicted).
     * Nhờ đó, lần truy vấn chi tiết thuốc tiếp theo sẽ buộc phải truy vấn lại DB để lấy giá mới nhất.
     */
    @CacheEvict(value = "medicines", key = "#medicine.id")
    public Medicine updateMedicine(Medicine medicine) {
        log.info("===> [CACHE EVICT] Đã kích hoạt @CacheEvict xóa cache 'medicines' key = {} do thuốc được cập nhật", medicine.getId());
        Medicine existing = medicineRepository.findById(medicine.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thuốc có ID: " + medicine.getId()));

        if (medicine.getName() != null && !medicine.getName().isBlank()) {
            existing.setName(medicine.getName());
        }
        if (medicine.getQuantity() != null) {
            existing.setQuantity(medicine.getQuantity());
        }
        if (medicine.getUnitPrice() != null) {
            existing.setUnitPrice(medicine.getUnitPrice());
        }

        Medicine updated = medicineRepository.save(existing);
        log.info("===> [DATABASE UPDATE] Đã lưu thông tin thuốc mới vào Database: ID={}, Giá={}", updated.getId(), updated.getUnitPrice());
        return updated;
    }

    public List<Medicine> getAllMedicines() {
        return medicineRepository.findAll();
    }

    public Medicine addMedicine(Medicine medicine) {
        return medicineRepository.save(medicine);
    }
}
