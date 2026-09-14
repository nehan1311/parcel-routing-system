package com.parcelrouting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.config.ActiveRoutingConfig;
import com.parcelrouting.config.ConfigService;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.RoutingDecision;
import com.parcelrouting.routing.RoutingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParcelServiceTest {

    private final ConfigService configService = mock(ConfigService.class);
    private final RoutingEngine routingEngine = mock(RoutingEngine.class);
    private final ParcelRepository parcelRepository = mock(ParcelRepository.class);
    private final ParcelService parcelService = new ParcelService(
            configService,
            routingEngine,
            parcelRepository,
            new ObjectMapper()
    );

    @Test
    void submitsNormalParcelAsRouted() {
        Parcel parcel = parcel(5, 500);
        RoutingConfig config = new RoutingConfig(1000, List.of());
        when(configService.getActiveConfigWithVersion()).thenReturn(new ActiveRoutingConfig(42L, config));
        when(routingEngine.evaluate(same(parcel), same(config)))
                .thenReturn(new RoutingDecision(false, "Regular", "regular-department"));
        when(parcelRepository.save(any(ParcelEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParcelEntity savedParcel = parcelService.submit(parcel);

        assertEquals(ParcelStatus.ROUTED, savedParcel.getStatus());
        assertEquals("Regular", savedParcel.getDepartment());
        assertEquals("Regular", savedParcel.getPredictedDepartment());
        assertEquals("regular-department", savedParcel.getMatchedRuleId());
        assertEquals(42L, savedParcel.getRoutingConfigVersionId());
        assertEquals("{\"fragile\":true}", savedParcel.getAttributesJson());
        verify(configService).getActiveConfigWithVersion();
        verify(routingEngine).evaluate(parcel, config);
        verify(parcelRepository).save(any(ParcelEntity.class));
    }

    @Test
    void submitsInsuranceRequiredParcelAsPendingApproval() {
        Parcel parcel = parcel(15, 2000);
        RoutingConfig config = new RoutingConfig(1000, List.of());
        when(configService.getActiveConfigWithVersion()).thenReturn(new ActiveRoutingConfig(43L, config));
        when(routingEngine.evaluate(same(parcel), same(config)))
                .thenReturn(new RoutingDecision(true, "Heavy", "heavy-department"));
        when(parcelRepository.save(any(ParcelEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParcelEntity savedParcel = parcelService.submit(parcel);

        assertEquals(ParcelStatus.PENDING_APPROVAL, savedParcel.getStatus());
        assertNull(savedParcel.getDepartment());
        assertEquals("Heavy", savedParcel.getPredictedDepartment());
        assertEquals("heavy-department", savedParcel.getMatchedRuleId());
        assertEquals(43L, savedParcel.getRoutingConfigVersionId());
        verify(configService).getActiveConfigWithVersion();
        verify(routingEngine).evaluate(parcel, config);
        verify(parcelRepository).save(any(ParcelEntity.class));
    }

    @Test
    void rejectsNegativeWeight() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parcelService.submit(parcel(-1, 100))
        );

        assertTrue(exception.getMessage().contains("weightKg"));
        verifyNoInteractions(configService, routingEngine, parcelRepository);
    }

    @Test
    void rejectsNegativeValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parcelService.submit(parcel(1, -1))
        );

        assertTrue(exception.getMessage().contains("valueEur"));
        verifyNoInteractions(configService, routingEngine, parcelRepository);
    }

    @Test
    void rejectsNullParcel() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parcelService.submit(null)
        );

        assertTrue(exception.getMessage().contains("Parcel must not be null"));
        verifyNoInteractions(configService, routingEngine, parcelRepository);
    }

    @Test
    void doesNotSaveWhenNoActiveConfigurationExists() {
        when(configService.getActiveConfigWithVersion())
                .thenThrow(new IllegalStateException("No active routing configuration exists"));

        assertThrows(IllegalStateException.class, () -> parcelService.submit(parcel(1, 100)));

        verify(configService).getActiveConfigWithVersion();
        verifyNoInteractions(routingEngine);
        verify(parcelRepository, never()).save(any(ParcelEntity.class));
    }

    @Test
    void doesNotSaveWhenRoutingFails() {
        Parcel parcel = parcel(1, 100);
        RoutingConfig config = new RoutingConfig(1000, List.of());
        when(configService.getActiveConfigWithVersion()).thenReturn(new ActiveRoutingConfig(44L, config));
        when(routingEngine.evaluate(same(parcel), same(config)))
                .thenThrow(new IllegalArgumentException("No matching routing rule found"));

        assertThrows(IllegalArgumentException.class, () -> parcelService.submit(parcel));

        verify(configService).getActiveConfigWithVersion();
        verify(routingEngine).evaluate(parcel, config);
        verify(parcelRepository, never()).save(any(ParcelEntity.class));
    }

    private Parcel parcel(double weightKg, double valueEur) {
        return new Parcel(weightKg, valueEur, null, Map.of("fragile", true));
    }
}
