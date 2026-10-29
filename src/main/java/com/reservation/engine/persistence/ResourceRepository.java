package com.reservation.engine.persistence;

import com.reservation.engine.domain.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {
    Page<Resource> findByTypeCode(String typeCode, Pageable pageable);
}
