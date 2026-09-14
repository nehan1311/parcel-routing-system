package com.parcelrouting.api;

import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.service.ApprovalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/parcels")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/pending-approval")
    @PreAuthorize("hasRole('INSURANCE_APPROVER')")
    public List<ParcelEntity> pendingApprovalParcels() {
        return approvalService.findPendingApproval();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('INSURANCE_APPROVER')")
    public ParcelEntity approve(@PathVariable Long id, Authentication authentication) {
        return approvalService.approve(id, authentication.getName());
    }
}
