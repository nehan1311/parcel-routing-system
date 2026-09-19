package com.parcelrouting.config;

public class MaterialChangeApprovalException extends ConfigVersionActivationException {

    public MaterialChangeApprovalException(int version, String reason) {
        super(version, reason);
    }
}
