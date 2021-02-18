package org.folio.rs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.folio.rs.client.ItemRequestsClient;
import org.folio.rs.client.ItemsClient;
import org.folio.rs.domain.dto.CheckInItem;
import org.folio.rs.domain.dto.ReturnItemResponse;
import org.folio.rs.domain.entity.RetrievalQueueRecord;
import org.folio.rs.dto.ContributorName;
import org.folio.rs.dto.Request;
import org.folio.rs.error.ItemReturnException;
import org.folio.rs.repository.RetrievalQueueRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@Service
@RequiredArgsConstructor
@Log4j2
public class ReturnItemService {

  private static final String BARCODE_QUERY_PROPERTY = "barcode==";

  private final ItemsClient itemsClient;
  private final ItemRequestsClient itemRequestsClient;
  private final RetrievalQueueRepository retrievalQueueRepository;
  private final CheckInItemService checkInItemService;

  public ReturnItemResponse returnItem(String remoteStorageConfigurationId, CheckInItem checkInItem) {
    log.info("Start return for item with barcode " + checkInItem.getItemBarcode());
    var itemReturnResponse = new ReturnItemResponse();
    var items = itemsClient.query(BARCODE_QUERY_PROPERTY + checkInItem.getItemBarcode());
    if (items.getTotalRecords() == 0) {
      throw new ItemReturnException("Item does not exist for barcode " + checkInItem.getItemBarcode());
    }
    var item = items.getResult().get(0);
    var requests = itemRequestsClient.getItemRequests(item.getId());
    if (requests.getTotalRecords() != 0) {
      var holdRecallRequests = requests.getRequests().stream()
        .filter(request -> request.getRequestType() == Request.RequestType.HOLD
        || request.getRequestType() == Request.RequestType.RECALL)
        .collect(toList());
      if (!holdRecallRequests.isEmpty()) {
        itemReturnResponse.isHoldRecallRequestExist(true);
      }
      var firstRequest = holdRecallRequests.stream()
        .filter(request -> request.getPosition() == 1)
        .findFirst();
      if (firstRequest.isPresent()) {
        var retrievalQueueRecord = getRetrievalRecord(firstRequest.get(), remoteStorageConfigurationId);
        retrievalQueueRepository.save(retrievalQueueRecord);
      }
    }
    checkInItemService.checkInItemByBarcode(remoteStorageConfigurationId, checkInItem);
    log.info("Return success for item with barcode " + checkInItem.getItemBarcode());
    return itemReturnResponse;
  }

  private RetrievalQueueRecord getRetrievalRecord(Request request, String remoteStorageId) {
    var retrievalRecord = new RetrievalQueueRecord();
    retrievalRecord.setId(UUID.randomUUID());
    var requestedItem = request.getItem();
    if (requestedItem != null) {
      retrievalRecord.setHoldId(requestedItem.getHoldingsRecordId());
      retrievalRecord.setItemBarcode(requestedItem.getBarcode());
      retrievalRecord.setInstanceTitle(requestedItem.getTitle());
      retrievalRecord.setInstanceAuthor(requestedItem
        .getContributorNames()
        .stream()
        .map(ContributorName::getName)
        .collect(Collectors.joining("; ")));
      retrievalRecord.setCallNumber(requestedItem.getCallNumber());
    }
    var requester = request.getRequester();
    if (requester != null) {
      retrievalRecord.setPatronBarcode(requester.getBarcode());
      retrievalRecord.setPatronName(requester.getFirstName() + " " + requester.getLastName());

    }
    var pickUpServicePoint = request.getPickupServicePoint();
    if (pickUpServicePoint != null) {
      retrievalRecord.setPickupLocation(pickUpServicePoint.getPickupLocation().toString());
    }
    retrievalRecord.setRequestStatus(request.getStatus().value());
    retrievalRecord.setRequestNote(request.getPatronComments());
    retrievalRecord.setCreatedDateTime(LocalDateTime.now());
    retrievalRecord.setRemoteStorageId(UUID.fromString(remoteStorageId));
    return retrievalRecord;
  }
}
