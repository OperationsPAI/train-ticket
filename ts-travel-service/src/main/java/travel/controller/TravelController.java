package travel.controller;

import static org.springframework.http.ResponseEntity.ok;

import edu.fudan.common.entity.TravelInfo;
import edu.fudan.common.entity.TripAllDetailInfo;
import edu.fudan.common.entity.TripInfo;
import edu.fudan.common.entity.TripResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import travel.service.TravelService;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/travelservice")
@Tag(name = "Travel", description = "Travel/Trip management APIs")
public class TravelController {

  @Autowired private TravelService travelService;

  private static final Logger LOGGER = LoggerFactory.getLogger(TravelController.class);

  @Operation(summary = "Welcome endpoint", description = "Returns a welcome message")
  @GetMapping(path = "/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ Travel Service ] !";
  }

  @Operation(
      summary = "Get train type by trip ID",
      description = "Retrieve train type information for a specific trip")
  @GetMapping(value = "/train_types/{tripId}")
  public HttpEntity getTrainTypeByTripId(
      @Parameter(description = "Trip ID") @PathVariable String tripId,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info(
        "[getTrainTypeByTripId][Get train Type by Trip id][TripId: {}]", tripId);
    return ok(travelService.getTrainTypeByTripId(tripId, headers));
  }

  @Operation(
      summary = "Get route by trip ID",
      description = "Retrieve route information for a specific trip")
  @GetMapping(value = "/routes/{tripId}")
  public HttpEntity getRouteByTripId(
      @Parameter(description = "Trip ID") @PathVariable String tripId,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[getRouteByTripId][Get Route By Trip ID][TripId: {}]", tripId);
    return ok(travelService.getRouteByTripId(tripId, headers));
  }

  @Operation(
      summary = "Get trips by route IDs",
      description = "Retrieve trips for multiple route IDs")
  @PostMapping(value = "/trips/routes")
  public HttpEntity getTripsByRouteId(
      @Parameter(description = "List of route IDs") @RequestBody ArrayList<String> routeIds,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info(
        "[getTripByRoute][Get Trips by Route ids][RouteIds: {}]", routeIds.size());
    return ok(travelService.getTripByRoute(routeIds, headers));
  }

  @Operation(summary = "Create trip", description = "Create a new trip")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Trip created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input data")
      })
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/trips")
  public HttpEntity<?> createTrip(
      @Parameter(description = "Trip information") @RequestBody TravelInfo routeIds,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[create][Create trip][TripId: {}]", routeIds.getTripId());
    return new ResponseEntity<>(travelService.create(routeIds, headers), HttpStatus.CREATED);
  }

  @Operation(
      summary = "Get trip by ID",
      description = "Retrieve a specific trip by its ID (no ticket info)")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/trips/{tripId}")
  public HttpEntity retrieve(
      @Parameter(description = "Trip ID") @PathVariable String tripId,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[retrieve][Retrieve trip][TripId: {}]", tripId);
    return ok(travelService.retrieve(tripId, headers));
  }

  @Operation(summary = "Update trip", description = "Update an existing trip")
  @CrossOrigin(origins = "*")
  @PutMapping(value = "/trips")
  public HttpEntity updateTrip(
      @Parameter(description = "Updated trip information") @RequestBody TravelInfo info,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[update][Update trip][TripId: {}]", info.getTripId());
    return ok(travelService.update(info, headers));
  }

  @Operation(summary = "Delete trip", description = "Delete a trip by ID")
  @CrossOrigin(origins = "*")
  @DeleteMapping(value = "/trips/{tripId}")
  public HttpEntity deleteTrip(
      @Parameter(description = "Trip ID to delete") @PathVariable String tripId,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[delete][Delete trip][TripId: {}]", tripId);
    return ok(travelService.delete(tripId, headers));
  }

  @Operation(summary = "Query available trips", description = "Query trips with remaining tickets")
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/trips/left")
  public HttpEntity queryInfo(
      @Parameter(description = "Query criteria") @RequestBody TripInfo info,
      @RequestHeader HttpHeaders headers) {
    if (info.getStartPlace() == null
        || info.getStartPlace().length() == 0
        || info.getEndPlace() == null
        || info.getEndPlace().length() == 0
        || info.getDepartureTime() == null) {
      TravelController.LOGGER.info("[query][Travel Query Fail][Something null]");
      ArrayList<TripResponse> errorList = new ArrayList<>();
      return ok(errorList);
    }
    TravelController.LOGGER.info("[query][Query TripResponse]");
    return ok(travelService.queryByBatch(info, headers));
  }

  @Operation(
      summary = "Query available trips (parallel)",
      description = "Query trips with remaining tickets using parallel processing")
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/trips/left_parallel")
  public HttpEntity queryInfoInparallel(
      @Parameter(description = "Query criteria") @RequestBody TripInfo info,
      @RequestHeader HttpHeaders headers) {
    if (info.getStartPlace() == null
        || info.getStartPlace().length() == 0
        || info.getEndPlace() == null
        || info.getEndPlace().length() == 0
        || info.getDepartureTime() == null) {
      TravelController.LOGGER.info("[queryInParallel][Travel Query Fail][Something null]");
      ArrayList<TripResponse> errorList = new ArrayList<>();
      return ok(errorList);
    }
    TravelController.LOGGER.info("[queryInParallel][Query TripResponse]");
    return ok(travelService.queryInParallel(info, headers));
  }

  @Operation(
      summary = "Get trip details",
      description = "Get all details for a specific trip including remaining tickets")
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/trip_detail")
  public HttpEntity getTripAllDetailInfo(
      @Parameter(description = "Trip detail query info") @RequestBody TripAllDetailInfo gtdi,
      @RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info(
        "[getTripAllDetailInfo][Get trip detail][TripId: {}]", gtdi.getTripId());
    return ok(travelService.getTripAllDetailInfo(gtdi, headers));
  }

  @Operation(summary = "Get all trips", description = "Retrieve all trips in the system")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/trips")
  public HttpEntity queryAll(@RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[queryAll][Query all trips]");
    return ok(travelService.queryAll(headers));
  }

  @Operation(
      summary = "Admin query all trips",
      description = "Admin endpoint to retrieve all trips with details")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/admin_trip")
  public HttpEntity adminQueryAll(@RequestHeader HttpHeaders headers) {
    TravelController.LOGGER.info("[adminQueryAll][Admin query all trips]");
    return ok(travelService.adminQueryAll(headers));
  }
}
