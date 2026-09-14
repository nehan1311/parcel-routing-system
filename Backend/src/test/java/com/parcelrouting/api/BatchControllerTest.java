package com.parcelrouting.api;

import com.parcelrouting.batch.BatchProcessor;
import com.parcelrouting.config.ConfigService;
import com.parcelrouting.service.ApprovalService;
import com.parcelrouting.service.ParcelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "parcel.security.operator-password=operator-test-only",
        "parcel.security.approver-password=approver-test-only",
        "parcel.security.admin-password=admin-test-only",
        "parcel.batch.max-upload-size=1KB"
})
@AutoConfigureMockMvc
class BatchControllerTest {

    private static final String OPERATOR_PASSWORD = "operator-test-only";
    private static final String APPROVER_PASSWORD = "approver-test-only";
    private static final String ADMIN_PASSWORD = "admin-test-only";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BatchProcessor batchProcessor;

    @MockBean
    private ParcelService parcelService;

    @MockBean
    private ApprovalService approvalService;

    @MockBean
    private ConfigService configService;

    @Test
    void operatorCanUploadJson() throws Exception {
        when(batchProcessor.process(any(InputStream.class), eq(BatchProcessor.InputFormat.JSON))).thenReturn(successResult());

        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.json", "[]"))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulRecords").value(1));
    }

    @Test
    void operatorCanUploadXml() throws Exception {
        when(batchProcessor.process(any(InputStream.class), eq(BatchProcessor.InputFormat.XML))).thenReturn(successResult());

        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.xml", "<Container/>"))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdParcelIds[0]").value(1));
    }

    @Test
    void approverIsForbidden() throws Exception {
        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.json", "[]"))
                        .with(basicAuthentication("approver", APPROVER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminIsForbidden() throws Exception {
        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.json", "[]"))
                        .with(basicAuthentication("admin", ADMIN_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/parcels/batch")
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A batch file is required"));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.json", ""))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Batch file must not be empty"));
    }

    @Test
    void rejectsUnsupportedFileExtension() throws Exception {
        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.csv", "weight,value"))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported batch file type; use .json or .xml"));
    }

    @Test
    void rejectsOversizedUpload() throws Exception {
        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(new MockMultipartFile("file", "parcels.json", "application/json", new byte[1025]))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Batch file exceeds the maximum allowed upload size"));
    }

    @Test
    void callsBatchProcessorOnceForValidUpload() throws Exception {
        when(batchProcessor.process(any(InputStream.class), eq(BatchProcessor.InputFormat.JSON))).thenReturn(successResult());

        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.json", "[]"))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isOk());

        verify(batchProcessor, times(1)).process(any(InputStream.class), eq(BatchProcessor.InputFormat.JSON));
    }

    @Test
    void handlesMalformedJsonWithoutInternalDetails() throws Exception {
        when(batchProcessor.process(any(InputStream.class), eq(BatchProcessor.InputFormat.JSON)))
                .thenThrow(new BatchProcessor.BatchProcessingException("Malformed JSON batch input"));

        mockMvc.perform(multipart("/api/parcels/batch")
                .file(file("parcels.json", "["))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON batch input"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("Exception"))));
    }

    @Test
    void handlesMalformedXmlUsingApiErrorFormat() throws Exception {
        when(batchProcessor.process(any(InputStream.class), eq(BatchProcessor.InputFormat.XML)))
                .thenThrow(new BatchProcessor.BatchProcessingException("Malformed XML batch input"));

        mockMvc.perform(multipart("/api/parcels/batch")
                        .file(file("parcels.xml", "<Container>"))
                        .with(basicAuthentication("operator", OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed XML batch input"));
    }

    private BatchProcessor.BatchResult successResult() {
        return new BatchProcessor.BatchResult(1, 1, 0, List.of(), List.of(1L));
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }

    private RequestPostProcessor basicAuthentication(String username, String password) {
        return request -> {
            String credentials = username + ":" + password;
            String encodedCredentials = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            request.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedCredentials);
            return request;
        };
    }
}
