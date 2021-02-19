package org.folio.rs.service;

import static org.folio.rs.util.MapperUtils.stringToUUIDSafe;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.persistence.EntityNotFoundException;
import javax.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.folio.rs.client.InventoryClient;
import org.folio.rs.client.UsersClient;
import org.folio.rs.domain.dto.Contributor;
import org.folio.rs.domain.dto.EffectiveCallNumberComponents;
import org.folio.rs.domain.dto.FilterData;
import org.folio.rs.domain.dto.Instance;
import org.folio.rs.domain.dto.Item;
import org.folio.rs.domain.dto.LocationMapping;
import org.folio.rs.domain.dto.MovedEventRequest;
import org.folio.rs.domain.dto.ResultList;
import org.folio.rs.domain.dto.RetrievalQueues;
import org.folio.rs.domain.dto.User;
import org.folio.rs.domain.entity.RetrievalQueueRecord;
import org.folio.rs.mapper.RetrievalQueueMapper;
import org.folio.rs.repository.RetrievalQueueRepository;
import org.folio.spring.data.OffsetRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
@Log4j2
@RequiredArgsConstructor
public class RetrievalQueueService {

  private static final String ID = "id";
  private static final String ITEM_BARCODE = "itemBarcode";
  private static final String RETRIEVED_DATE_TIME = "retrievedDateTime";
  private static final String REMOTE_STORAGE_ID = "remoteStorageId";
  private static final String REQUEST_DATE_TIME = "createdDateTime";
  private static final String PAGED_REQUEST = "Paged";
  private static final String NOT_FOUND = " not found";
  private final RetrievalQueueRepository retrievalQueueRepository;
  private final RetrievalQueueMapper retrievalQueueMapper;
  private final LocationMappingsService locationMappingsService;
  private final InventoryClient inventoryClient;
  private final UsersClient usersClient;


  public RetrievalQueues getRetrievals(FilterData filterData) {
    var queueRecords = retrievalQueueRepository.findAll(getCriteriaSpecification(filterData),
        new OffsetRequest(filterData.getOffset(), filterData.getLimit(), Sort.unsorted()));
    return retrievalQueueMapper.mapEntitiesToRetrievalQueueCollection(queueRecords);
  }

  public void setRetrievedById(String retrievalQueueId) {
    Optional<RetrievalQueueRecord> retrievalQueue = retrievalQueueRepository.findOne(Specification.where(hasId(retrievalQueueId).and(notRetrievedSpecification())));
    if (retrievalQueue.isEmpty()) {
      throw new EntityNotFoundException("Retrieval queue record with id " + retrievalQueueId + NOT_FOUND);
    }
    saveRetrievalQueueWithCurrentDate(retrievalQueue.get());
  }

  public void setRetrievedByBarcode(String barcode) {
    Optional<RetrievalQueueRecord> retrievalQueueRecord = retrievalQueueRepository.findOne(Specification.where(hasBarcode(barcode).and(notRetrievedSpecification())));
    if (retrievalQueueRecord.isEmpty()) {
      throw new EntityNotFoundException("Retrieval queue record with item barcode " + barcode + NOT_FOUND);
    }
    saveRetrievalQueueWithCurrentDate(retrievalQueueRecord.get());
  }

  public void processRetrievalQueueRecord(MovedEventRequest movedEventRequest) {
    if (PAGED_REQUEST.equals(movedEventRequest.getItemStatusName())) {
      log.info("Process moved request with id " + movedEventRequest.getHoldId());
      Item item = getOriginalItemByBarcode(movedEventRequest);
      LocationMapping locationMapping = getLocationMapping(item);
      if (Objects.nonNull(locationMapping)) {
        log.info("Item location is remote, saving retrieval queue record");
        processRetrievalQueueRecord(movedEventRequest, item, locationMapping);
      }
    }
  }

  private void processRetrievalQueueRecord(MovedEventRequest movedEventRequest, Item item, LocationMapping locationMapping) {
    RetrievalQueueRecord record = buildRetrievalQueueRecord(movedEventRequest, item,
        getUserByRequesterId(movedEventRequest), locationMapping, getInstanceByInstanceId(item));
    retrievalQueueRepository.save(record);
  }

  private Specification<RetrievalQueueRecord> getCriteriaSpecification(FilterData filterData) {
    return (record, criteriaQuery, builder) -> {
      final Collection<Predicate> predicates = new ArrayList<>();
      if (Boolean.TRUE.equals(filterData.getIsPresented())) {
        predicates.add(builder.isNotNull(record.get(RETRIEVED_DATE_TIME)));
      }
      if (Boolean.FALSE.equals(filterData.getIsPresented())) {
        predicates.add(builder.isNull(record.get(RETRIEVED_DATE_TIME)));
      }
      if (Objects.nonNull(filterData.getStorageId())) {
        predicates.add(builder.equal(record.get(REMOTE_STORAGE_ID), stringToUUIDSafe(filterData.getStorageId())));
      }
      if (Objects.nonNull(filterData.getCreateDate())) {
        predicates.add(builder.equal(record.get(REQUEST_DATE_TIME), LocalDateTime.parse(filterData.getCreateDate())));
      }
      return builder.and(predicates.toArray(new Predicate[0]));
    };
  }

  private void saveRetrievalQueueWithCurrentDate(RetrievalQueueRecord record) {
    record.setRetrievedDateTime(LocalDateTime.now());
    retrievalQueueRepository.save(record);
  }

  private Specification<RetrievalQueueRecord> hasBarcode(String barcode) {
    return (record, criteria, builder) -> builder.equal(record.get(ITEM_BARCODE), barcode);
  }

  private Specification<RetrievalQueueRecord> notRetrievedSpecification() {
    return (record, criteria, builder) -> builder.isNull(record.get(RETRIEVED_DATE_TIME));
  }

  private Specification<RetrievalQueueRecord> hasId(String id) {
    return (record, criteria, builder) -> builder.equal(record.get(ID), stringToUUIDSafe(id));
  }

  private LocationMapping getLocationMapping(Item item) {
    return locationMappingsService.getMappingByFolioLocationId(item.getEffectiveLocationId());
  }

  private Item getOriginalItemByBarcode(MovedEventRequest movedEventRequest) {
    ResultList<Item> items = inventoryClient.getItem("barcode==" + movedEventRequest.getItemBarCode());
    if (Objects.isNull(items)) {
      throw new EntityNotFoundException("Item with barcode " + movedEventRequest.getItemBarCode() + NOT_FOUND);
    }
    return items.getResult().get(0);
  }

  private User getUserByRequesterId(MovedEventRequest movedEventRequest) {
    ResultList<User> users = usersClient.query("id==" + movedEventRequest.getRequesterId());
    if (Objects.isNull(users)) {
      throw new EntityNotFoundException("User with id " + movedEventRequest.getRequesterId() + NOT_FOUND);
    }
    return users.getResult().get(0);
  }

  private Instance getInstanceByInstanceId(Item item) {
    ResultList<Instance> instances = inventoryClient.getInstance("id==" + item.getInstanceId());
    if (Objects.isNull(instances)) {
      throw new EntityNotFoundException("Instance with id " + item.getInstanceId() + NOT_FOUND);
    }
    return instances.getResult().get(0);
  }

  private RetrievalQueueRecord buildRetrievalQueueRecord(MovedEventRequest movedEventRequest, Item item, User patron, LocationMapping mapping, Instance instance) {
    return RetrievalQueueRecord.builder()
        .holdId(movedEventRequest.getHoldId())
        .patronBarcode(patron.getBarcode())
        .patronName(patron.getUsername())
        .callNumber(getCallNumber(item))
        .itemBarcode(movedEventRequest.getItemBarCode())
        .createdDateTime(LocalDateTime.now())
        .pickupLocation(movedEventRequest.getPickupServicePointId())
        .requestStatus(movedEventRequest.getRequestStatus())
        .requestNote(movedEventRequest.getRequestNote())
        .remoteStorageId(stringToUUIDSafe(mapping.getConfigurationId()))
        .instanceTitle(instance.getTitle())
        .instanceAuthor(String.join(";", getInstanceAuthorsNames(instance)))
        .build();
  }

  private List<String> getInstanceAuthorsNames(Instance instance) {
    return instance.getContributors().stream()
        .map(Contributor::getName)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private String getCallNumber(Item item) {
    EffectiveCallNumberComponents components = item.getEffectiveCallNumberComponents();
    return Objects.nonNull(components) ? components.getCallNumber() : null;
  }
}
