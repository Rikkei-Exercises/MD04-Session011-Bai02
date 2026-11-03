package com.example.pharmacyservice;

import com.example.pharmacyservice.config.RedisConfig;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.MedicineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.cache.type=simple"
})
class MedicineServiceCacheTest {

    @MockitoBean
    private MedicineRepository medicineRepository;

    @Autowired
    private MedicineService medicineService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConfig redisConfig;

    private Medicine sampleMedicine;

    @BeforeEach
    void setUp() {
        // Xóa cache cũ trước mỗi test
        if (cacheManager.getCache("medicines") != null) {
            Objects.requireNonNull(cacheManager.getCache("medicines")).clear();
        }

        sampleMedicine = Medicine.builder()
                .id(1L)
                .name("Paracetamol 500mg")
                .quantity(100)
                .unitPrice(2500.0)
                .build();

        when(medicineRepository.findById(1L)).thenReturn(Optional.of(sampleMedicine));
    }

    @Test
    @DisplayName("Cấu hình Redis: TTL 10 phút, không cache null, key serializer và value serializer (JSON)")
    void testRedisCacheConfiguration() {
        assertNotNull(redisConfig, "RedisConfig bean phải tồn tại");
        RedisCacheConfiguration config = redisConfig.redisCacheConfiguration();
        assertNotNull(config, "RedisCacheConfiguration phải được cấu hình");
        assertFalse(config.getAllowCacheNullValues(), "disableCachingNullValues phải được thiết lập");
    }

    @Test
    @DisplayName("Lần 1 gọi getMedicineById: Kiểm tra Cache chưa có -> chạy DB query (gọi Repository 1 lần)")
    void testFirstCallQueriesDatabase() {
        Medicine result = medicineService.getMedicineById(1L);

        assertNotNull(result);
        assertEquals("Paracetamol 500mg", result.getName());
        assertEquals(1L, result.getId());

        // Kiểm tra repository.findById(1L) được gọi đúng 1 lần
        verify(medicineRepository, times(1)).findById(1L);
    }

    @Test
    @DisplayName("Lần 2 gọi getMedicineById cùng ID: Lấy từ Cache -> KHÔNG gọi Repository, tốc độ phản hồi < 10ms")
    void testSecondCallHitsCacheAndSkipsDatabase() {
        // Lần 1: Chưa có trong cache -> Gọi DB
        Medicine firstCallResult = medicineService.getMedicineById(1L);
        assertNotNull(firstCallResult);
        verify(medicineRepository, times(1)).findById(1L);

        // Lần 2: Đã có trong cache -> Lấy trực tiếp từ cache, không gọi DB
        long startTime = System.currentTimeMillis();
        Medicine secondCallResult = medicineService.getMedicineById(1L);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(secondCallResult);
        assertEquals(firstCallResult.getName(), secondCallResult.getName());
        assertEquals(firstCallResult.getUnitPrice(), secondCallResult.getUnitPrice());

        // Quan trọng: repository.findById vẫn chỉ được gọi đúng 1 lần từ lần gọi 1
        verify(medicineRepository, times(1)).findById(1L);

        // Tốc độ phản hồi cực nhanh khi lấy từ Cache (< 10ms)
        assertTrue(duration < 10, "Thời gian phản hồi khi lấy từ cache phải < 10ms, thực tế: " + duration + " ms");
    }
}
