package com.parcelrouting.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.config.ActiveRoutingConfig;
import com.parcelrouting.config.ConfigService;
import com.parcelrouting.monitoring.RoutingDecisionLogger;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelRepository;
import com.parcelrouting.routing.RoutingConfig;
import com.parcelrouting.routing.RoutingDecision;
import com.parcelrouting.routing.RoutingEngine;
import com.parcelrouting.service.ParcelService;
import com.parcelrouting.service.ParcelValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(BatchTransactionIsolationTest.TransactionTestConfiguration.class)
class BatchTransactionIsolationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private BatchProcessor batchProcessor;

    @org.springframework.beans.factory.annotation.Autowired
    private ConfigService configService;

    @org.springframework.beans.factory.annotation.Autowired
    private RoutingEngine routingEngine;

    @org.springframework.beans.factory.annotation.Autowired
    private ParcelRepository parcelRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TrackingTransactionManager transactionManager;

    private final List<ParcelEntity> persistedParcels = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(configService, routingEngine, parcelRepository);
        persistedParcels.clear();
        transactionManager.reset();

        when(configService.getActiveConfigWithVersion())
                .thenReturn(new ActiveRoutingConfig(1L, new RoutingConfig(1000, List.of())));
        when(routingEngine.evaluate(any(), any()))
                .thenReturn(new RoutingDecision(false, "Regular", "regular-department"));
        when(parcelRepository.save(any(ParcelEntity.class))).thenAnswer(invocation -> {
            ParcelEntity parcel = invocation.getArgument(0);
            ReflectionTestUtils.setField(parcel, "id", (long) persistedParcels.size() + 1);
            persistedParcels.add(parcel);
            return parcel;
        });
    }

    @Test
    void validInvalidValidRecordsCommitTheValidRecords() {
        BatchProcessor.BatchResult result = assertDoesNotThrow(() -> processJson("""
                [{"weightKg":1,"valueEur":10,"destinationCountry":"DE","attributes":{}},
                 {"weightKg":1,"valueEur":10,"destinationCountry":"Narnia","attributes":{}},
                 {"weightKg":1,"valueEur":10,"destinationCountry":"NL","attributes":{}}]
                """));

        assertEquals(2, result.successfulRecords());
        assertEquals(1, result.failedRecords());
        assertEquals(List.of("DE", "NL"), persistedParcels.stream().map(ParcelEntity::getDestinationCountry).toList());
        assertEquals(1, transactionManager.commits());
        assertEquals(0, transactionManager.rollbacks());
    }

    @Test
    void invalidFirstRecordDoesNotPreventLaterValidRecord() {
        BatchProcessor.BatchResult result = assertDoesNotThrow(() -> processJson("""
                [{"weightKg":1,"valueEur":10,"destinationCountry":"Narnia","attributes":{}},
                 {"weightKg":1,"valueEur":10,"destinationCountry":"DE","attributes":{}}]
                """));

        assertEquals(1, result.successfulRecords());
        assertEquals(1, result.failedRecords());
        assertEquals(List.of("DE"), persistedParcels.stream().map(ParcelEntity::getDestinationCountry).toList());
        assertEquals(1, transactionManager.commits());
    }

    @Test
    void multipleInvalidRecordsDoNotPreventValidRecords() {
        BatchProcessor.BatchResult result = assertDoesNotThrow(() -> processJson("""
                [{"weightKg":1,"valueEur":10,"destinationCountry":"Narnia","attributes":{}},
                 {"weightKg":1,"valueEur":10,"destinationCountry":"DE","attributes":{}},
                 {"weightKg":1,"valueEur":10,"destinationCountry":"Europe","attributes":{}},
                 {"weightKg":1,"valueEur":10,"destinationCountry":"NL","attributes":{}}]
                """));

        assertEquals(2, result.successfulRecords());
        assertEquals(2, result.failedRecords());
        assertEquals(List.of("DE", "NL"), persistedParcels.stream().map(ParcelEntity::getDestinationCountry).toList());
        assertEquals(1, transactionManager.commits());
    }

    private BatchProcessor.BatchResult processJson(String content) {
        return batchProcessor.process(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), BatchProcessor.InputFormat.JSON);
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionTestConfiguration {
        @Bean
        TrackingTransactionManager transactionManager() {
            return new TrackingTransactionManager();
        }

        @Bean
        ConfigService configService() {
            return org.mockito.Mockito.mock(ConfigService.class);
        }

        @Bean
        RoutingEngine routingEngine() {
            return org.mockito.Mockito.mock(RoutingEngine.class);
        }

        @Bean
        ParcelRepository parcelRepository() {
            return org.mockito.Mockito.mock(ParcelRepository.class);
        }

        @Bean
        RoutingDecisionLogger routingDecisionLogger() {
            return org.mockito.Mockito.mock(RoutingDecisionLogger.class);
        }

        @Bean
        ParcelValidator parcelValidator() {
            return new ParcelValidator();
        }

        @Bean
        ParcelService parcelService(
                ConfigService configService,
                RoutingEngine routingEngine,
                ParcelRepository parcelRepository,
                RoutingDecisionLogger routingDecisionLogger,
                ParcelValidator parcelValidator
        ) {
            return new ParcelService(
                    configService,
                    routingEngine,
                    parcelRepository,
                    new ObjectMapper(),
                    routingDecisionLogger,
                    parcelValidator
            );
        }

        @Bean
        BatchProcessor batchProcessor(ParcelService parcelService, ParcelValidator parcelValidator) {
            return new BatchProcessor(parcelService, new ObjectMapper(), parcelValidator);
        }
    }

    static class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TrackingTransaction> currentTransaction = new ThreadLocal<>();
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            TrackingTransaction transaction = currentTransaction.get();
            return transaction == null ? new TrackingTransaction() : transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TrackingTransaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TrackingTransaction trackingTransaction = (TrackingTransaction) transaction;
            trackingTransaction.active = true;
            currentTransaction.set(trackingTransaction);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            currentTransaction.remove();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            currentTransaction.remove();
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((TrackingTransaction) status.getTransaction()).rollbackOnly = true;
        }

        int commits() {
            return commits;
        }

        int rollbacks() {
            return rollbacks;
        }

        void reset() {
            commits = 0;
            rollbacks = 0;
            currentTransaction.remove();
        }
    }

    static class TrackingTransaction implements SmartTransactionObject {
        private boolean active;
        private boolean rollbackOnly;

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }

        @Override
        public void flush() {
        }
    }
}
