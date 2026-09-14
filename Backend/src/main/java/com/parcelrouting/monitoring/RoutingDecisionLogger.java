package com.parcelrouting.monitoring;

import com.parcelrouting.parcel.ParcelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class RoutingDecisionLogger {

    private final Logger logger;

    public RoutingDecisionLogger() {
        this(LoggerFactory.getLogger(RoutingDecisionLogger.class));
    }

    RoutingDecisionLogger(Logger logger) {
        this.logger = logger;
    }

    public void logCompletedDecision(ParcelEntity parcel) {
        logger.atInfo()
                .addKeyValue("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .addKeyValue("parcelId", parcel.getId())
                .addKeyValue("routingStatus", parcel.getStatus())
                .addKeyValue("predictedDepartment", parcel.getPredictedDepartment())
                .addKeyValue("matchedRuleId", parcel.getMatchedRuleId())
                .addKeyValue("routingConfigVersion", parcel.getRoutingConfigVersionId())
                .addKeyValue("insuranceRequired", parcel.getStatus().name().equals("PENDING_APPROVAL"))
                .log("routing_decision_completed");
    }
}
