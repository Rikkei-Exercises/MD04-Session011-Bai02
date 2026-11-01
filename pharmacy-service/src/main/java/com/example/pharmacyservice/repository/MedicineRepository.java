package com.example.pharmacyservice.repository;

import com.example.pharmacyservice.model.Medicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MedicineRepository extends JpaRepository<Medicine, Long> {
    Optional<Medicine> findByName(String name);
    Optional<Medicine> findByNameIgnoreCase(String name);
    Optional<Medicine> findFirstByNameContainingIgnoreCase(String name);
}
