package com.parcelrouting.parcel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;

public interface ParcelRepository extends JpaRepository<ParcelEntity, Long> {

    List<ParcelEntity> findByStatus(ParcelStatus status);

    List<ParcelEntity> findByStatusInAndCreatedAtAfter(List<ParcelStatus> statuses, Instant createdAt);
}
