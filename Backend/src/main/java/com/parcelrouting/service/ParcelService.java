package com.parcelrouting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.config.ActiveRoutingConfig;
import com.parcelrouting.config.ConfigService;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.routing.RoutingDecision;
import com.parcelrouting.routing.RoutingEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ParcelService {

    private final ConfigService configService;
    private final RoutingEngine routingEngine;
    private final ParcelRepository parcelRepository;
    private final ObjectMapper objectMapper;

    public ParcelService(
            ConfigService configService,
            RoutingEngine routingEngine,
            ParcelRepository parcelRepository,
            ObjectMapper objectMapper
    ) {
        this.configService = configService;
        this.routingEngine = routingEngine;
        this.parcelRepository = parcelRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ParcelEntity submit(Parcel parcel) {
        validateParcel(parcel);

        ActiveRoutingConfig activeConfig = configService.getActiveConfigWithVersion();
        RoutingDecision decision = routingEngine.evaluate(parcel, activeConfig.routingConfig());

        ParcelStatus status = decision.insuranceRequired()
                ? ParcelStatus.PENDING_APPROVAL
                : ParcelStatus.ROUTED;
        String department = decision.insuranceRequired() ? null : decision.predictedDepartment();

        ParcelEntity parcelEntity = new ParcelEntity(
                parcel.weightKg(),
                parcel.valueEur(),
                parcel.destinationCountry(),
                serializeAttributes(parcel),
                status,
                department,
                decision.predictedDepartment(),
                decision.matchedRuleId(),
                activeConfig.versionId(),
                Instant.now(),
                null,
                null
        );

        return parcelRepository.save(parcelEntity);
    }

    private void validateParcel(Parcel parcel) {
        if (parcel == null) {
            throw new IllegalArgumentException("Parcel must not be null");
        }
        if (!(parcel.weightKg() >= 0)) {
            throw new IllegalArgumentException("Parcel weightKg must be greater than or equal to zero");
        }
        if (!(parcel.valueEur() >= 0)) {
            throw new IllegalArgumentException("Parcel valueEur must be greater than or equal to zero");
        }
        if (parcel.attributes() == null) {
            throw new IllegalArgumentException("Parcel attributes must not be null");
        }
    }

    private String serializeAttributes(Parcel parcel) {
        try {
            return objectMapper.writeValueAsString(parcel.attributes());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Parcel attributes cannot be serialized", exception);
        }
    }
}
