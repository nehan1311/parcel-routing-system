package com.parcelrouting.service;

import com.parcelrouting.config.ConfigService;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.routing.RoutingEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalServiceTest {

    private final ParcelRepository parcelRepository = mock(ParcelRepository.class);
    private final ApprovalService approvalService = new ApprovalService(parcelRepository);

    @Test
    void approvesPendingParcelAndRecordsApprovalDetails() {
        ParcelEntity parcel = parcel(ParcelStatus.PENDING_APPROVAL, "Heavy", "heavy-rule", 41L);
        Instant beforeApproval = Instant.now();
        when(parcelRepository.findById(1L)).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(parcel)).thenReturn(parcel);

        ParcelEntity approved = approvalService.approve(1L, "approver");

        assertSame(parcel, approved);
        assertEquals(ParcelStatus.ROUTED, approved.getStatus());
        assertEquals("Heavy", approved.getDepartment());
        assertEquals("approver", approved.getApprovedBy());
        assertNotNull(approved.getApprovedAt());
        assertTrue(!approved.getApprovedAt().isBefore(beforeApproval));
        verify(parcelRepository).save(parcel);
    }

    @Test
    void rejectsRoutedParcelWithoutSaving() {
        ParcelEntity parcel = parcel(ParcelStatus.ROUTED, "Regular", "regular-rule", 42L);
        when(parcelRepository.findById(2L)).thenReturn(Optional.of(parcel));

        assertThrows(IllegalStateException.class, () -> approvalService.approve(2L, "approver"));

        verify(parcelRepository, never()).save(any(ParcelEntity.class));
    }

    @Test
    void rejectsCreatedParcelWithoutSaving() {
        ParcelEntity parcel = parcel(ParcelStatus.CREATED, "Regular", "regular-rule", 43L);
        when(parcelRepository.findById(3L)).thenReturn(Optional.of(parcel));

        assertThrows(IllegalStateException.class, () -> approvalService.approve(3L, "approver"));

        verify(parcelRepository, never()).save(any(ParcelEntity.class));
    }

    @Test
    void rejectsMissingParcelWithoutSaving() {
        when(parcelRepository.findById(4L)).thenReturn(Optional.empty());

        NoSuchElementException exception = assertThrows(
                NoSuchElementException.class,
                () -> approvalService.approve(4L, "approver")
        );

        assertTrue(exception.getMessage().contains("Parcel not found"));
        verify(parcelRepository, never()).save(any(ParcelEntity.class));
    }

    @Test
    void rejectsNullParcelId() {
        assertThrows(IllegalArgumentException.class, () -> approvalService.approve(null, "approver"));

        verifyNoInteractions(parcelRepository);
    }

    @Test
    void rejectsBlankApprover() {
        assertThrows(IllegalArgumentException.class, () -> approvalService.approve(5L, "  "));

        verifyNoInteractions(parcelRepository);
    }

    @Test
    void preservesRoutingSnapshotFieldsWhenApproved() {
        ParcelEntity parcel = parcel(ParcelStatus.PENDING_APPROVAL, "Insurance", "insurance-rule", 44L);
        when(parcelRepository.findById(6L)).thenReturn(Optional.of(parcel));
        when(parcelRepository.save(parcel)).thenReturn(parcel);

        approvalService.approve(6L, "approver");

        assertEquals("Insurance", parcel.getPredictedDepartment());
        assertEquals("insurance-rule", parcel.getMatchedRuleId());
        assertEquals(44L, parcel.getRoutingConfigVersionId());
    }

    @Test
    void hasOnlyParcelRepositoryAsADependency() {
        Field[] fields = ApprovalService.class.getDeclaredFields();

        assertEquals(1, fields.length);
        assertEquals(ParcelRepository.class, fields[0].getType());
        assertTrue(fields[0].getType() != RoutingEngine.class);
        assertTrue(fields[0].getType() != ConfigService.class);
    }

    private ParcelEntity parcel(
            ParcelStatus status,
            String predictedDepartment,
            String matchedRuleId,
            Long routingConfigVersionId
    ) {
        return new ParcelEntity(
                10.0,
                2000.0,
                "NL",
                "{}",
                status,
                null,
                predictedDepartment,
                matchedRuleId,
                routingConfigVersionId,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null
        );
    }
}
