package com.parcelrouting.api;

import com.parcelrouting.parcel.Parcel;
import com.parcelrouting.parcel.ParcelEntity;
import com.parcelrouting.service.ParcelService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/parcels")
public class ParcelController {

    private final ParcelService parcelService;

    public ParcelController(ParcelService parcelService) {
        this.parcelService = parcelService;
    }

    @PostMapping
    public ResponseEntity<ParcelSubmissionResponse> submit(
            @Valid @RequestBody ParcelSubmissionRequest request
    ) {
        Parcel parcel = new Parcel(
                request.weightKg(),
                request.valueEur(),
                request.destinationCountry(),
                request.attributes()
        );
        ParcelEntity savedParcel = parcelService.submit(parcel);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ParcelSubmissionResponse.from(savedParcel));
    }
}
