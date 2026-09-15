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
import com.parcelrouting.monitoring.RoutingDecisionLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ParcelService {

    private final ConfigService configService;
    private final RoutingEngine routingEngine;
    private final ParcelRepository parcelRepository;
    private final ObjectMapper objectMapper;
    private final RoutingDecisionLogger routingDecisionLogger;
    private final ParcelValidator parcelValidator;

    public ParcelService(
            ConfigService configService,
            RoutingEngine routingEngine,
            ParcelRepository parcelRepository,
            ObjectMapper objectMapper,
            RoutingDecisionLogger routingDecisionLogger,
            ParcelValidator parcelValidator
    ) {
        this.configService = configService;
        this.routingEngine = routingEngine;
        this.parcelRepository = parcelRepository;
        this.objectMapper = objectMapper;
        this.routingDecisionLogger = routingDecisionLogger;
        this.parcelValidator = parcelValidator;
    }

    @Transactional
    public ParcelEntity submit(Parcel parcel) {
        parcelValidator.validate(parcel);

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

        ParcelEntity savedParcel = parcelRepository.save(parcelEntity);
        routingDecisionLogger.logCompletedDecision(savedParcel);
        return savedParcel;
    }

    private String serializeAttributes(Parcel parcel) {
        try {
            return objectMapper.writeValueAsString(parcel.attributes());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Parcel attributes cannot be serialized", exception);
        }
    }
}
