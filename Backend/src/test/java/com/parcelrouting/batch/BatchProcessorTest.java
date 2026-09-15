package com.parcelrouting.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.parcel.ParcelStatus;
import com.parcelrouting.parcel.CountryCodes;
import com.parcelrouting.service.ParcelService;
import com.parcelrouting.service.ParcelValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchProcessorTest {

    private final ParcelService parcelService = mock(ParcelService.class);
    private final BatchProcessor batchProcessor = new BatchProcessor(parcelService, new ObjectMapper(), new ParcelValidator());

    @Test
    void processesMultipleValidJsonRecords() {
        doReturn(savedParcel(1L, ParcelStatus.ROUTED), savedParcel(2L, ParcelStatus.ROUTED))
                .when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.processJson(input("""
                [{"weightKg":5,"valueEur":100,"destinationCountry":" nl ","attributes":{}},
                 {"weightKg":6,"valueEur":200,"destinationCountry":"BE","attributes":{"fragile":true}}]
                """));

        assertEquals(2, result.totalRecords());
        assertEquals(2, result.successfulRecords());
        assertEquals(0, result.failedRecords());
        assertEquals(List.of(1L, 2L), result.createdParcelIds());
        ArgumentCaptor<Parcel> parcels = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService, times(2)).submit(parcels.capture());
        assertEquals("NL", parcels.getAllValues().get(0).destinationCountry());
        assertEquals("BE", parcels.getAllValues().get(1).destinationCountry());
    }

    @Test
    void processesMultipleValidXmlRecords() {
        doReturn(savedParcel(3L, ParcelStatus.ROUTED), savedParcel(4L, ParcelStatus.ROUTED))
                .when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.processXml(input("""
                <Container><parcels>
                  <Parcel><Weight>5</Weight><Value>100</Value><DestinationCountry>DE</DestinationCountry><Recipient>One</Recipient></Parcel>
                  <Parcel><Weight>6</Weight><Value>200</Value><DestinationCountry>NL</DestinationCountry><Recipient>Two</Recipient></Parcel>
                </parcels></Container>
                """));

        assertEquals(2, result.totalRecords());
        assertEquals(2, result.successfulRecords());
        ArgumentCaptor<Parcel> parcels = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService, times(2)).submit(parcels.capture());
        assertEquals("One", parcels.getAllValues().get(0).attributes().get("Recipient"));
        assertEquals("DE", parcels.getAllValues().get(0).destinationCountry());
        assertEquals("NL", parcels.getAllValues().get(1).destinationCountry());
    }

    @Test
    void normalizesXmlDestinationCountry() {
        doReturn(savedParcel(11L, ParcelStatus.ROUTED)).when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.processXml(input("""
                <Container><Parcel><Weight>5</Weight><Value>100</Value>
                  <DestinationCountry>  de  </DestinationCountry>
                </Parcel></Container>
                """));

        assertEquals(1, result.successfulRecords());
        ArgumentCaptor<Parcel> parcel = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService).submit(parcel.capture());
        assertEquals("DE", parcel.getValue().destinationCountry());
    }

    @Test
    void reportsInvalidXmlCountryAsRecordError() {
        when(parcelService.submit(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel parcel = invocation.getArgument(0);
            if (!CountryCodes.isValid(parcel.destinationCountry())) {
                throw new IllegalArgumentException("destinationCountry must be a valid ISO 3166-1 alpha-2 country code");
            }
            return savedParcel(12L, ParcelStatus.ROUTED);
        });

        BatchProcessor.BatchResult result = batchProcessor.processXml(input("""
                <Container><Parcel><Weight>5</Weight><Value>100</Value>
                  <DestinationCountry>Narnia</DestinationCountry>
                </Parcel></Container>
                """));

        assertEquals(0, result.successfulRecords());
        assertEquals(1, result.failedRecords());
        assertTrue(result.errors().getFirst().message().contains("valid ISO 3166-1 alpha-2"));
    }

    @Test
    void reportsMissingAndBlankXmlCountryAsRecordErrors() {
        when(parcelService.submit(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel parcel = invocation.getArgument(0);
            if (parcel.destinationCountry() == null || parcel.destinationCountry().isBlank()) {
                throw new IllegalArgumentException("destinationCountry must not be blank");
            }
            return savedParcel(13L, ParcelStatus.ROUTED);
        });

        BatchProcessor.BatchResult result = batchProcessor.processXml(input("""
                <Container>
                  <Parcel><Weight>5</Weight><Value>100</Value></Parcel>
                  <Parcel><Weight>6</Weight><Value>200</Value><DestinationCountry>  </DestinationCountry></Parcel>
                </Container>
                """));

        assertEquals(0, result.successfulRecords());
        assertEquals(2, result.failedRecords());
    }

    @Test
    void continuesAfterMixedValidAndInvalidXmlCountries() {
        when(parcelService.submit(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel parcel = invocation.getArgument(0);
            if (!CountryCodes.isValid(parcel.destinationCountry())) {
                throw new IllegalArgumentException("destinationCountry must be a valid ISO 3166-1 alpha-2 country code");
            }
            return savedParcel(parcel.destinationCountry().equals("DE") ? 14L : 15L, ParcelStatus.ROUTED);
        });

        BatchProcessor.BatchResult result = batchProcessor.processXml(input("""
                <Container>
                  <Parcel><Weight>5</Weight><Value>100</Value><DestinationCountry>DE</DestinationCountry></Parcel>
                  <Parcel><Weight>6</Weight><Value>200</Value><DestinationCountry>Narnia</DestinationCountry></Parcel>
                  <Parcel><Weight>7</Weight><Value>300</Value><DestinationCountry>NL</DestinationCountry></Parcel>
                </Container>
                """));

        assertEquals(3, result.totalRecords());
        assertEquals(2, result.successfulRecords());
        assertEquals(1, result.failedRecords());
        assertEquals(List.of(14L, 15L), result.createdParcelIds());
    }

    @Test
    void continuesAfterMixedValidAndInvalidRecords() {
        when(parcelService.submit(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel parcel = invocation.getArgument(0);
            if (!CountryCodes.isValid(parcel.destinationCountry())) {
                throw new IllegalArgumentException("destinationCountry must be a valid ISO 3166-1 alpha-2 country code");
            }
            return savedParcel(parcel.destinationCountry().equals("DE") ? 5L : 6L, ParcelStatus.ROUTED);
        });

        BatchProcessor.BatchResult result = batchProcessor.processJson(input("""
                [{"weightKg":5,"valueEur":100,"destinationCountry":"de","attributes":{}},
                 {"weightKg":6,"valueEur":100,"destinationCountry":"Narnia","attributes":{}},
                 {"weightKg":7,"valueEur":200,"destinationCountry":"NL","attributes":{}}]
                """));

        assertEquals(3, result.totalRecords());
        assertEquals(2, result.successfulRecords());
        assertEquals(1, result.failedRecords());
        assertEquals(2, result.errors().getFirst().recordNumber());
        verify(parcelService, times(2)).submit(any(Parcel.class));
    }

    @Test
    void reportsInvalidWeightAndValuePerRecord() {
        BatchProcessor.BatchResult result = batchProcessor.processJson(input("""
                [{"weightKg":-1,"valueEur":100,"destinationCountry":"DE","attributes":{}},
                 {"weightKg":1,"valueEur":-100,"destinationCountry":"NL","attributes":{}}]
                """));

        assertEquals(2, result.failedRecords());
        assertTrue(result.errors().get(0).message().contains("weightKg"));
        assertTrue(result.errors().get(1).message().contains("valueEur"));
    }

    @Test
    void preservesInsuranceRequiredRoutingOutcomeFromParcelService() {
        doReturn(savedParcel(6L, ParcelStatus.PENDING_APPROVAL)).when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.processJson(input("""
                [{"weightKg":15,"valueEur":2000,"destinationCountry":"DE","attributes":{}}]
                """));

        assertEquals(1, result.successfulRecords());
        assertEquals(List.of(6L), result.createdParcelIds());
    }

    @Test
    void preservesNormalRoutingOutcomeFromParcelService() {
        doReturn(savedParcel(7L, ParcelStatus.ROUTED)).when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.processJson(input("""
                [{"weightKg":5,"valueEur":100,"destinationCountry":"DE","attributes":{}}]
                """));

        assertEquals(1, result.successfulRecords());
        assertEquals(List.of(7L), result.createdParcelIds());
    }

    @Test
    void reusesParcelServiceForRoutingAndPersistence() {
        doReturn(savedParcel(8L, ParcelStatus.ROUTED)).when(parcelService).submit(any(Parcel.class));

        batchProcessor.processJson(input("""
                [{"weightKg":5,"valueEur":100,"destinationCountry":"NL","attributes":{"priority":"high"}}]
                """));

        ArgumentCaptor<Parcel> parcel = ArgumentCaptor.forClass(Parcel.class);
        verify(parcelService).submit(parcel.capture());
        assertEquals(5.0, parcel.getValue().weightKg());
        assertEquals("high", parcel.getValue().attributes().get("priority"));
    }

    @Test
    void parsesLargeJsonBatchFromAStreamingInput() {
        String record = "{\"weightKg\":1,\"valueEur\":1,\"destinationCountry\":\"DE\",\"attributes\":{}}";
        String json = "[" + String.join(",", java.util.Collections.nCopies(250, record)) + "]";
        doReturn(savedParcel(9L, ParcelStatus.ROUTED)).when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.process(new OneByteInputStream(json), BatchProcessor.InputFormat.JSON);

        assertEquals(250, result.totalRecords());
        assertEquals(250, result.successfulRecords());
        verify(parcelService, times(250)).submit(any(Parcel.class));
    }

    @Test
    void parsesLargeXmlBatchFromAStreamingInput() {
        String record = "<Parcel><Weight>1</Weight><Value>1</Value><DestinationCountry>DE</DestinationCountry></Parcel>";
        String xml = "<Container><parcels>" + String.join("", java.util.Collections.nCopies(250, record))
                + "</parcels></Container>";
        doReturn(savedParcel(10L, ParcelStatus.ROUTED)).when(parcelService).submit(any(Parcel.class));

        BatchProcessor.BatchResult result = batchProcessor.process(new OneByteInputStream(xml), BatchProcessor.InputFormat.XML);

        assertEquals(250, result.totalRecords());
        assertEquals(250, result.successfulRecords());
        verify(parcelService, times(250)).submit(any(Parcel.class));
    }

    @Test
    void rejectsMalformedInputWithClearBatchLevelError() {
        BatchProcessor.BatchProcessingException exception = assertThrows(
                BatchProcessor.BatchProcessingException.class,
                () -> batchProcessor.processJson(input("[{\"weightKg\":1"))
        );

        assertTrue(exception.getMessage().contains("Malformed JSON"));
    }

    @Test
    void rejectsMalformedXmlWithClearBatchLevelError() {
        BatchProcessor.BatchProcessingException exception = assertThrows(
                BatchProcessor.BatchProcessingException.class,
                () -> batchProcessor.processXml(input("<Container><parcels><Parcel><Weight>1</Weight>"))
        );

        assertTrue(exception.getMessage().contains("Malformed XML"));
    }

    @Test
    void processesWithinATransactionSoMalformedDocumentsRollBackEarlierWrites() throws NoSuchMethodException {
        assertTrue(BatchProcessor.class
                .getMethod("process", InputStream.class, BatchProcessor.InputFormat.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void handlesEmptyInputSafely() {
        BatchProcessor.BatchResult result = batchProcessor.processJson(input("[]"));

        assertEquals(0, result.totalRecords());
        assertEquals(0, result.successfulRecords());
        assertEquals(0, result.failedRecords());
    }

    private InputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private ParcelEntity savedParcel(Long id, ParcelStatus status) {
        ParcelEntity entity = mock(ParcelEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getStatus()).thenReturn(status);
        return entity;
    }

    private static class OneByteInputStream extends InputStream {

        private final byte[] content;
        private int index;

        private OneByteInputStream(String content) {
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            return index < content.length ? content[index++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int next = read();
            if (next == -1) {
                return -1;
            }
            buffer[offset] = (byte) next;
            return 1;
        }
    }
}
