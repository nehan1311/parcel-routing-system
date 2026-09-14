package com.parcelrouting.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parcel.security")
public class SecurityProperties {

    private String operatorPassword;
    private String approverPassword;
    private String adminPassword;

    public String getOperatorPassword() {
        return operatorPassword;
    }

    public void setOperatorPassword(String operatorPassword) {
        this.operatorPassword = operatorPassword;
    }

    public String getApproverPassword() {
        return approverPassword;
    }

    public void setApproverPassword(String approverPassword) {
        this.approverPassword = approverPassword;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}
