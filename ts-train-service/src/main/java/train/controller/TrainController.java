package train.controller;

import static org.springframework.http.ResponseEntity.ok;

import edu.fudan.common.util.Response;
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
import train.entity.TrainType;
import train.service.TrainService;

@RestController
@RequestMapping("/api/v1/trainservice")
@Tag(name = "Train", description = "Train type management APIs")
public class TrainController {

  @Autowired private TrainService trainService;

  private static final Logger LOGGER = LoggerFactory.getLogger(TrainController.class);

  @Operation(summary = "Welcome endpoint", description = "Returns a welcome message")
  @GetMapping(path = "/trains/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ Train Service ] !";
  }

  @Operation(summary = "Create train type", description = "Create a new train type")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Train type created successfully"),
        @ApiResponse(responseCode = "400", description = "Train type already exists")
      })
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/trains")
  public HttpEntity create(
      @Parameter(description = "Train type data") @RequestBody TrainType trainType,
      @RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[create][Create train][TrainTypeId: {}]", trainType.getId());
    TrainType res = trainService.create(trainType, headers);
    if (res != null) {
      return ok(new Response(1, "create success", res));
    } else {
      return ok(new Response(0, "train type already exist", trainType));
    }
  }

  @Operation(summary = "Get train type by ID", description = "Retrieve a train type by its ID")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/trains/{id}")
  public HttpEntity retrieve(
      @Parameter(description = "Train type ID") @PathVariable String id,
      @RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[retrieve][Retrieve train][TrainTypeId: {}]", id);
    TrainType trainType = trainService.retrieve(id, headers);
    if (trainType == null) {
      return ok(new Response(0, "here is no TrainType with the trainType id: " + id, null));
    } else {
      return ok(new Response(1, "success", trainType));
    }
  }

  @Operation(summary = "Get train type by name", description = "Retrieve a train type by its name")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/trains/byName/{name}")
  public HttpEntity retrieveByName(
      @Parameter(description = "Train type name") @PathVariable String name,
      @RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[retrieveByName][Retrieve train][TrainTypeName: {}]", name);
    TrainType trainType = trainService.retrieveByName(name, headers);
    if (trainType == null) {
      return ok(new Response(0, "here is no TrainType with the trainType name: " + name, null));
    } else {
      return ok(new Response(1, "success", trainType));
    }
  }

  @Operation(
      summary = "Get train types by names",
      description = "Retrieve multiple train types by their names")
  @CrossOrigin(origins = "*")
  @PostMapping(value = "/trains/byNames")
  public HttpEntity retrieveByName(
      @Parameter(description = "List of train type names") @RequestBody List<String> names,
      @RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[retrieveByNames][Retrieve train][TrainTypeNames: {}]", names);
    List<TrainType> trainTypes = trainService.retrieveByNames(names, headers);
    if (trainTypes == null) {
      return ok(new Response(0, "here is no TrainTypes with the trainType names: " + names, null));
    } else {
      return ok(new Response(1, "success", trainTypes));
    }
  }

  @Operation(summary = "Update train type", description = "Update an existing train type")
  @CrossOrigin(origins = "*")
  @PutMapping(value = "/trains")
  public HttpEntity update(
      @Parameter(description = "Updated train type data") @RequestBody TrainType trainType,
      @RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[update][Update train][TrainTypeId: {}]", trainType.getId());
    boolean isUpdateSuccess = trainService.update(trainType, headers);
    if (isUpdateSuccess) {
      return ok(new Response(1, "update success", isUpdateSuccess));
    } else {
      return ok(new Response(0, "there is no trainType with the trainType id", isUpdateSuccess));
    }
  }

  @Operation(summary = "Delete train type", description = "Delete a train type by ID")
  @CrossOrigin(origins = "*")
  @DeleteMapping(value = "/trains/{id}")
  public HttpEntity delete(
      @Parameter(description = "Train type ID to delete") @PathVariable String id,
      @RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[delete][Delete train][TrainTypeId: {}]", id);
    boolean isDeleteSuccess = trainService.delete(id, headers);
    if (isDeleteSuccess) {
      return ok(new Response(1, "delete success", isDeleteSuccess));
    } else {
      return ok(new Response(0, "there is no train according to id", null));
    }
  }

  @Operation(
      summary = "Get all train types",
      description = "Retrieve all train types in the system")
  @CrossOrigin(origins = "*")
  @GetMapping(value = "/trains")
  public HttpEntity query(@RequestHeader HttpHeaders headers) {
    TrainController.LOGGER.info("[query][Query train]");
    List<TrainType> trainTypes = trainService.query(headers);
    if (trainTypes != null && !trainTypes.isEmpty()) {
      return ok(new Response(1, "success", trainTypes));
    } else {
      return ok(new Response(0, "no content", trainTypes));
    }
  }
}
