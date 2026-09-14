package com.parcelrouting.batch;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelrouting.api.ParcelSubmissionResponse;
import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.service.ParcelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BatchProcessor {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {
    };

    private final ParcelService parcelService;
    private final ObjectMapper objectMapper;

    public BatchProcessor(ParcelService parcelService, ObjectMapper objectMapper) {
        this.parcelService = parcelService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BatchResult process(InputStream input, InputFormat format) {
        if (input == null) {
            throw new BatchProcessingException("Batch input must not be null");
        }
        if (format == null) {
            throw new BatchProcessingException("Batch input format must not be null");
        }

        return switch (format) {
            case JSON -> processJson(input);
            case XML -> processXml(input);
        };
    }

    @Transactional
    public BatchResult processJson(InputStream input) {
        BatchResultBuilder result = new BatchResultBuilder();
        JsonFactory jsonFactory = objectMapper.getFactory();

        try (JsonParser parser = jsonFactory.createParser(input)) {
            JsonToken firstToken = parser.nextToken();
            if (firstToken == null) {
                return result.build();
            }
            if (firstToken != JsonToken.START_ARRAY) {
                throw new BatchProcessingException("JSON batch input must be an array of parcels");
            }

            while (true) {
                JsonToken recordToken = parser.nextToken();
                if (recordToken == JsonToken.END_ARRAY) {
                    break;
                }
                if (recordToken == null) {
                    throw new BatchProcessingException("Malformed JSON batch input");
                }
                result.incrementTotal();
                if (recordToken != JsonToken.START_OBJECT) {
                    result.addError("Expected a parcel object");
                    parser.skipChildren();
                    continue;
                }

                try {
                    processRecord(readJsonParcel(parser), result);
                } catch (RecordValidationException exception) {
                    result.addError(exception.getMessage());
                    skipToEndOfJsonObject(parser);
                } catch (RuntimeException exception) {
                    if (exception instanceof BatchProcessingException) {
                        throw exception;
                    }
                    result.addError(messageFor(exception));
                }
            }
            return result.build();
        } catch (IOException exception) {
            throw new BatchProcessingException("Malformed JSON batch input", exception);
        }
    }

    @Transactional
    public BatchResult processXml(InputStream input) {
        BatchResultBuilder result = new BatchResultBuilder();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "Parcel".equals(reader.getLocalName())) {
                    result.incrementTotal();
                    try {
                        processRecord(readXmlParcel(reader), result);
                    } catch (RecordValidationException exception) {
                        result.addError(exception.getMessage());
                    } catch (RuntimeException exception) {
                        if (exception instanceof BatchProcessingException) {
                            throw exception;
                        }
                        result.addError(messageFor(exception));
                    }
                }
            }
            reader.close();
            return result.build();
        } catch (XMLStreamException exception) {
            throw new BatchProcessingException("Malformed XML batch input", exception);
        }
    }

    private Parcel readJsonParcel(JsonParser parser) throws IOException {
        Double weightKg = null;
        Double valueEur = null;
        String destinationCountry = null;
        Map<String, Object> attributes = new LinkedHashMap<>();

        while (true) {
            JsonToken fieldToken = parser.nextToken();
            if (fieldToken == JsonToken.END_OBJECT) {
                break;
            }
            if (fieldToken == null || fieldToken != JsonToken.FIELD_NAME) {
                throw new BatchProcessingException("Malformed JSON batch input");
            }

            String fieldName = parser.currentName();
            if (parser.nextToken() == null) {
                throw new BatchProcessingException("Malformed JSON batch input");
            }
            switch (fieldName) {
                case "weightKg" -> weightKg = readNumber(parser, "weightKg");
                case "valueEur" -> valueEur = readNumber(parser, "valueEur");
                case "destinationCountry" -> destinationCountry = parser.getValueAsString();
                case "attributes" -> {
                    if (parser.currentToken() == JsonToken.VALUE_NULL) {
                        throw new RecordValidationException("attributes must not be null");
                    }
                    attributes = objectMapper.readValue(parser, ATTRIBUTES_TYPE);
                    if (attributes == null) {
                        throw new RecordValidationException("attributes must not be null");
                    }
                }
                default -> parser.skipChildren();
            }
        }
        return parcel(weightKg, valueEur, destinationCountry, attributes);
    }

    private Parcel readXmlParcel(XMLStreamReader reader) throws XMLStreamException {
        String weight = null;
        String value = null;
        String currentElement = null;
        Map<String, Object> attributes = new LinkedHashMap<>();

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                currentElement = reader.getLocalName();
            } else if (event == XMLStreamConstants.CHARACTERS && !reader.isWhiteSpace() && currentElement != null) {
                String text = reader.getText().trim();
                if (!text.isEmpty()) {
                    if ("Weight".equals(currentElement)) {
                        weight = text;
                    } else if ("Value".equals(currentElement)) {
                        value = text;
                    } else {
                        attributes.put(currentElement, text);
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("Parcel".equals(reader.getLocalName())) {
                    return parcel(parseXmlNumber(weight, "weightKg"), parseXmlNumber(value, "valueEur"), null, attributes);
                }
                currentElement = null;
            }
        }
        throw new BatchProcessingException("Malformed XML batch input: Parcel element is not closed");
    }

    private Double readNumber(JsonParser parser, String fieldName) throws IOException {
        if (!parser.currentToken().isNumeric()) {
            throw new RecordValidationException(fieldName + " must be a number");
        }
        return parser.getDoubleValue();
    }

    private void skipToEndOfJsonObject(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.END_OBJECT) {
            return;
        }

        int nestedStructures = 0;
        while (parser.nextToken() != null) {
            if (parser.currentToken() == JsonToken.START_OBJECT || parser.currentToken() == JsonToken.START_ARRAY) {
                nestedStructures++;
            } else if (parser.currentToken() == JsonToken.END_OBJECT) {
                if (nestedStructures == 0) {
                    return;
                }
                nestedStructures--;
            } else if (parser.currentToken() == JsonToken.END_ARRAY && nestedStructures > 0) {
                nestedStructures--;
            }
        }
    }

    private Double parseXmlNumber(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RecordValidationException(fieldName + " must be provided");
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new RecordValidationException(fieldName + " must be a number");
        }
    }

    private Parcel parcel(Double weightKg, Double valueEur, String destinationCountry, Map<String, Object> attributes) {
        if (weightKg == null) {
            throw new RecordValidationException("weightKg must be provided");
        }
        if (valueEur == null) {
            throw new RecordValidationException("valueEur must be provided");
        }
        if (!(weightKg >= 0)) {
            throw new RecordValidationException("weightKg must be greater than or equal to zero");
        }
        if (!(valueEur >= 0)) {
            throw new RecordValidationException("valueEur must be greater than or equal to zero");
        }
        return new Parcel(weightKg, valueEur, destinationCountry, attributes);
    }

    private void processRecord(Parcel parcel, BatchResultBuilder result) {
        ParcelEntity saved = parcelService.submit(parcel);
        result.addSuccess(saved);
    }

    private String messageFor(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    public enum InputFormat {
        JSON,
        XML
    }

    public record BatchResult(
            int totalRecords,
            int successfulRecords,
            int failedRecords,
            List<RecordError> errors,
            List<Long> createdParcelIds,
            List<ParcelSubmissionResponse> createdParcels
    ) {
        public BatchResult(
                int totalRecords,
                int successfulRecords,
                int failedRecords,
                List<RecordError> errors,
                List<Long> createdParcelIds
        ) {
            this(totalRecords, successfulRecords, failedRecords, errors, createdParcelIds, List.of());
        }
    }

    public record RecordError(int recordNumber, String message) {
    }

    public static class BatchProcessingException extends IllegalArgumentException {

        public BatchProcessingException(String message) {
            super(message);
        }

        public BatchProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class RecordValidationException extends IllegalArgumentException {

        private RecordValidationException(String message) {
            super(message);
        }
    }

    private static class BatchResultBuilder {

        private int totalRecords;
        private int successfulRecords;
        private final List<RecordError> errors = new ArrayList<>();
        private final List<Long> createdParcelIds = new ArrayList<>();
        private final List<ParcelSubmissionResponse> createdParcels = new ArrayList<>();

        void incrementTotal() {
            totalRecords++;
        }

        void addSuccess(ParcelEntity parcel) {
            successfulRecords++;
            createdParcelIds.add(parcel.getId());
            createdParcels.add(ParcelSubmissionResponse.from(parcel));
        }

        void addError(String message) {
            errors.add(new RecordError(totalRecords, message));
        }

        BatchResult build() {
            return new BatchResult(
                    totalRecords,
                    successfulRecords,
                    errors.size(),
                    List.copyOf(errors),
                    List.copyOf(createdParcelIds),
                    List.copyOf(createdParcels)
            );
        }
    }
}
