package com.parcelrouting.service;

import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ApprovalService {

    private final ParcelRepository parcelRepository;

    public ApprovalService(ParcelRepository parcelRepository) {
        this.parcelRepository = parcelRepository;
    }

    @Transactional(readOnly = true)
    public List<ParcelEntity> findPendingApproval() {
        return parcelRepository.findByStatus(ParcelStatus.PENDING_APPROVAL);
    }

    @Transactional
    public ParcelEntity approve(Long parcelId, String approver) {
        validateApprovalRequest(parcelId, approver);

        ParcelEntity parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new NoSuchElementException("Parcel not found: " + parcelId));

        if (parcel.getStatus() != ParcelStatus.PENDING_APPROVAL) {
            throw new ApprovalNotPendingException();
        }

        parcel.setStatus(ParcelStatus.ROUTED);
        parcel.setDepartment(parcel.getPredictedDepartment());
        parcel.setApprovedBy(approver);
        parcel.setApprovedAt(Instant.now());

        return parcelRepository.save(parcel);
    }

    private void validateApprovalRequest(Long parcelId, String approver) {
        if (parcelId == null) {
            throw new IllegalArgumentException("Parcel ID must not be null");
        }
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("Approver must not be blank");
        }
    }

    public static class ApprovalNotPendingException extends IllegalStateException {

        public ApprovalNotPendingException() {
            super("Only parcels pending approval can be approved");
        }
    }
}
