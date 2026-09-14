package com.parcelrouting.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "parcel.batch")
public class BatchUploadProperties {

    private DataSize maxUploadSize = DataSize.ofMegabytes(10);

    public DataSize getMaxUploadSize() {
        return maxUploadSize;
    }

    public void setMaxUploadSize(DataSize maxUploadSize) {
        this.maxUploadSize = maxUploadSize;
    }
}
