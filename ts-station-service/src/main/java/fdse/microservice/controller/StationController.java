package fdse.microservice.controller;

import static org.springframework.http.ResponseEntity.ok;

import edu.fudan.common.util.Response;
import fdse.microservice.entity.Station;
import fdse.microservice.service.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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

@RestController
@RequestMapping("/api/v1/stationservice")
@Tag(name = "Station", description = "Railway station management APIs")
public class StationController {

  @Autowired private StationService stationService;

  private static final Logger LOGGER = LoggerFactory.getLogger(StationController.class);

  @Operation(summary = "Welcome endpoint", description = "Returns a welcome message")
  @GetMapping(path = "/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ Station Service ] !";
  }

  @Operation(summary = "Get all stations", description = "Retrieve all stations in the system")
  @GetMapping(value = "/stations")
  public HttpEntity query(@RequestHeader HttpHeaders headers) {
    return ok(stationService.query(headers));
  }

  @Operation(summary = "Create station", description = "Create a new station")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Station created successfully"),
        @ApiResponse(responseCode = "400", description = "Station already exists")
      })
  @PostMapping(value = "/stations")
  public ResponseEntity<Response> create(
      @Parameter(description = "Station data") @RequestBody Station station,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info("[create][Create station][name: {}]", station.getName());
    return new ResponseEntity<>(stationService.create(station, headers), HttpStatus.CREATED);
  }

  @Operation(summary = "Update station", description = "Update an existing station")
  @PutMapping(value = "/stations")
  public HttpEntity update(
      @Parameter(description = "Updated station data") @RequestBody Station station,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info("[update][Update station][StationId: {}]", station.getId());
    return ok(stationService.update(station, headers));
  }

  @Operation(summary = "Delete station", description = "Delete a station by ID")
  @DeleteMapping(value = "/stations/{stationsId}")
  public ResponseEntity<Response> delete(
      @Parameter(description = "Station ID to delete") @PathVariable String stationsId,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info("[delete][Delete station][StationId: {}]", stationsId);
    return ok(stationService.delete(stationsId, headers));
  }

  @Operation(summary = "Get station ID by name", description = "Query station ID by station name")
  @GetMapping(value = "/stations/id/{stationNameForId}")
  public HttpEntity queryForStationId(
      @Parameter(description = "Station name") @PathVariable(value = "stationNameForId")
          String stationName,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info(
        "[queryForId][Query for station id][StationName: {}]", stationName);
    return ok(stationService.queryForId(stationName, headers));
  }

  @Operation(
      summary = "Get station IDs by names (batch)",
      description = "Query multiple station IDs by station names")
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/stations/idlist")
  public HttpEntity queryForIdBatch(
      @Parameter(description = "List of station names") @RequestBody List<String> stationNameList,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info(
        "[queryForIdBatch][Query stations for id batch][StationNameNumbers: {}]",
        stationNameList.size());
    return ok(stationService.queryForIdBatch(stationNameList, headers));
  }

  @Operation(summary = "Get station name by ID", description = "Query station name by station ID")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/stations/name/{stationIdForName}")
  public HttpEntity queryById(
      @Parameter(description = "Station ID") @PathVariable(value = "stationIdForName")
          String stationId,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info("[queryById][Query stations By Id][Id: {}]", stationId);
    return ok(stationService.queryById(stationId, headers));
  }

  @Operation(
      summary = "Get station names by IDs (batch)",
      description = "Query multiple station names by station IDs")
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/stations/namelist")
  public HttpEntity queryForNameBatch(
      @Parameter(description = "List of station IDs") @RequestBody List<String> stationIdList,
      @RequestHeader HttpHeaders headers) {
    StationController.LOGGER.info(
        "[queryByIdBatch][Query stations for name batch][StationIdNumbers: {}]",
        stationIdList.size());
    return ok(stationService.queryByIdBatch(stationIdList, headers));
  }
}
