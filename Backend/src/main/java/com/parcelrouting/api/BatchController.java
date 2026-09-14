package com.parcelrouting.api;

import com.parcelrouting.batch.BatchProcessor;
import com.parcelrouting.batch.BatchUploadProperties;
import com.parcelrouting.batch.BatchUploadTooLargeException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

@RestController
@RequestMapping("/api/parcels")
@EnableConfigurationProperties(BatchUploadProperties.class)
public class BatchController {

    private final BatchProcessor batchProcessor;
    private final BatchUploadProperties batchUploadProperties;

    public BatchController(BatchProcessor batchProcessor, BatchUploadProperties batchUploadProperties) {
        this.batchProcessor = batchProcessor;
        this.batchUploadProperties = batchUploadProperties;
    }

    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OPERATOR')")
    public BatchProcessor.BatchResult upload(@RequestParam(name = "file", required = false) MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("A batch file is required");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Batch file must not be empty");
        }
        if (file.getSize() > batchUploadProperties.getMaxUploadSize().toBytes()) {
            throw new BatchUploadTooLargeException("Batch file exceeds the maximum allowed upload size");
        }

        BatchProcessor.InputFormat format = formatFor(file.getOriginalFilename());
        try {
            return batchProcessor.process(file.getInputStream(), format);
        } catch (IOException exception) {
            throw new BatchProcessor.BatchProcessingException("Batch file could not be read", exception);
        }
    }

    private BatchProcessor.InputFormat formatFor(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Batch file must have a .json or .xml extension");
        }

        String normalizedFilename = filename.toLowerCase(Locale.ROOT);
        if (normalizedFilename.endsWith(".json")) {
            return BatchProcessor.InputFormat.JSON;
        }
        if (normalizedFilename.endsWith(".xml")) {
            return BatchProcessor.InputFormat.XML;
        }
        throw new IllegalArgumentException("Unsupported batch file type; use .json or .xml");
    }
}
