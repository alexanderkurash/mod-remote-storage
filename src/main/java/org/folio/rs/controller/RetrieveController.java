package org.folio.rs.controller;

import java.time.format.DateTimeParseException;

import javax.persistence.EntityNotFoundException;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;

import org.folio.rs.domain.dto.FilterData;
import org.folio.rs.domain.dto.RetrievalQueues;
import org.folio.rs.rest.resource.RetrievalsApi;
import org.folio.rs.service.RetrievalQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/remote-storage")
public class RetrieveController implements RetrievalsApi {
  private static final String RETRIEVAL_QUEUE_NOT_FOUND = "Retrieval queue not found";
  private static final String WRONG_DATE_FORMAT_MESSAGE = "Wrong date format for accession queue";

  private final RetrievalQueueService retrievalQueueService;

  @Override
  public ResponseEntity<RetrievalQueues> getRetrievals(@Valid Boolean retrieved, @Valid String storageId,
      @Valid String createdDateTime, @Min(0) @Max(2147483647) @Valid Integer offset,
      @Min(0) @Max(2147483647) @Valid Integer limit) {
    var accessionQueues = retrievalQueueService.getRetrievals(getFilterData(retrieved, storageId, createdDateTime, offset, limit));
    return new ResponseEntity<>(accessionQueues, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<String> setRetrievedById(
      @Pattern(regexp = "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[1-5][a-fA-F0-9]{3}-[89abAB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}$") String accessionId) {
    retrievalQueueService.setRetrievedById(accessionId);
    return ResponseEntity.noContent()
      .build();
  }

  @Override
  public ResponseEntity<String> setRetrievedByBarcode(String barcode) {
    retrievalQueueService.setRetrievedByBarcode(barcode);
    return ResponseEntity.noContent()
      .build();
  }

  @ExceptionHandler({ EntityNotFoundException.class })
  public ResponseEntity<String> handleNotFoundExceptions() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(RETRIEVAL_QUEUE_NOT_FOUND);
  }

  @ExceptionHandler({ DateTimeParseException.class })
  public ResponseEntity<String> handleDateTimeFormatExceptions() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(WRONG_DATE_FORMAT_MESSAGE);
  }

  private FilterData getFilterData(Boolean retrieved, String storageId, String createdDate, Integer offset, Integer limit) {
    return FilterData.builder()
      .isPresented(retrieved)
      .storageId(storageId)
      .createDate(createdDate)
      .offset(offset)
      .limit(limit)
      .build();
  }
}