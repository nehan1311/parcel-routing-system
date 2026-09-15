package com.parcelrouting.api;

import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.security.SecurityConfiguration;
import com.parcelrouting.service.ParcelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ParcelController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SecurityConfiguration.class)
class ParcelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ParcelService parcelService;

    @Test
    void submitsNormalParcelAndReturnsRoutedResponse() throws Exception {
        ParcelEntity responseParcel = parcelEntity(
                101L,
                ParcelStatus.ROUTED,
                "Regular",
                "Regular",
                "regular-department",
                1L
        );
        when(parcelService.submit(any(Parcel.class))).thenReturn(responseParcel);

        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(101)))
                .andExpect(jsonPath("$.status", is("ROUTED")))
                .andExpect(jsonPath("$.department", is("Regular")))
                .andExpect(jsonPath("$.predictedDepartment", is("Regular")))
                .andExpect(jsonPath("$.matchedRuleId", is("regular-department")))
                .andExpect(jsonPath("$.routingConfigVersionId", is(1)))
                .andExpect(jsonPath("$.insuranceRequired", is(false)));

        ArgumentCaptor<Parcel> parcelCaptor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService).submit(parcelCaptor.capture());
        Parcel submittedParcel = parcelCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(15.0, submittedParcel.weightKg());
        org.junit.jupiter.api.Assertions.assertEquals(2000.0, submittedParcel.valueEur());
        org.junit.jupiter.api.Assertions.assertEquals("NL", submittedParcel.destinationCountry());
    }

    @Test
    void submitsInsuranceParcelAndReturnsPendingApprovalResponse() throws Exception {
        ParcelEntity responseParcel = parcelEntity(
                102L,
                ParcelStatus.PENDING_APPROVAL,
                null,
                "Heavy",
                "heavy-department",
                1L
        );
        when(parcelService.submit(any(Parcel.class))).thenReturn(responseParcel);

        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(102)))
                .andExpect(jsonPath("$.status", is("PENDING_APPROVAL")))
                .andExpect(jsonPath("$.department").value(nullValue()))
                .andExpect(jsonPath("$.predictedDepartment", is("Heavy")))
                .andExpect(jsonPath("$.matchedRuleId", is("heavy-department")))
                .andExpect(jsonPath("$.routingConfigVersionId", is(1)))
                .andExpect(jsonPath("$.insuranceRequired", is(true)));

        verify(parcelService).submit(any(Parcel.class));
    }

    @Test
    void rejectsNegativeWeight() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("15.0", "-1.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void rejectsNegativeValue() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("2000.0", "-1.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void normalizesLowercaseDestinationCountry() throws Exception {
        ParcelEntity responseParcel = parcelEntity(
                101L, ParcelStatus.ROUTED, "Regular", "Regular", "regular-department", 1L);
        when(parcelService.submit(any(Parcel.class))).thenReturn(responseParcel);

        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("\"NL\"", "\"de\"")))
                .andExpect(status().isCreated());

        ArgumentCaptor<Parcel> captor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService).submit(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("DE", captor.getValue().destinationCountry());
    }

    @Test
    void normalizesSurroundingDestinationCountryWhitespace() throws Exception {
        ParcelEntity responseParcel = parcelEntity(
                101L, ParcelStatus.ROUTED, "Regular", "Regular", "regular-department", 1L);
        when(parcelService.submit(any(Parcel.class))).thenReturn(responseParcel);

        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("\"NL\"", "\"  de  \"")))
                .andExpect(status().isCreated());

        ArgumentCaptor<Parcel> captor = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService).submit(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("DE", captor.getValue().destinationCountry());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Germanyyyyy", "Europe", "Narnia"})
    void rejectsInvalidDestinationCountry(String country) throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("\"NL\"", "\"" + country + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Invalid request: destinationCountry must be a valid ISO 3166-1 alpha-2 country code")));

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankDestinationCountry(String country) throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("\"NL\"", "\"" + country + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Invalid request: destinationCountry must not be blank")));

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void rejectsNullDestinationCountry() throws Exception {
        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson().replace("\"NL\"", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Invalid request: destinationCountry must not be blank")));

        verify(parcelService, never()).submit(any(Parcel.class));
    }

    @Test
    void returnsServerErrorWhenSubmissionFails() throws Exception {
        when(parcelService.submit(any(Parcel.class)))
                .thenThrow(new IllegalStateException("No active routing configuration exists"));

        mockMvc.perform(post("/api/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message", is("Parcel submission could not be completed")));

        verify(parcelService).submit(any(Parcel.class));
    }

    private ParcelEntity parcelEntity(
            Long id,
            ParcelStatus status,
            String department,
            String predictedDepartment,
            String matchedRuleId,
            Long routingConfigVersionId
    ) {
        ParcelEntity parcelEntity = mock(ParcelEntity.class);
        when(parcelEntity.getId()).thenReturn(id);
        when(parcelEntity.getStatus()).thenReturn(status);
        when(parcelEntity.getDepartment()).thenReturn(department);
        when(parcelEntity.getPredictedDepartment()).thenReturn(predictedDepartment);
        when(parcelEntity.getMatchedRuleId()).thenReturn(matchedRuleId);
        when(parcelEntity.getRoutingConfigVersionId()).thenReturn(routingConfigVersionId);
        return parcelEntity;
    }

    private String validRequestJson() {
        return """
                {
                  "weightKg": 15.0,
                  "valueEur": 2000.0,
                  "destinationCountry": "NL",
                  "attributes": {
                    "fragile": true
                  }
                }
                """;
    }
}
