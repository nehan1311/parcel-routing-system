package com.parcelrouting.parcel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParcelRepository extends JpaRepository<ParcelEntity, Long> {

    List<ParcelEntity> findByStatus(ParcelStatus status);
}
