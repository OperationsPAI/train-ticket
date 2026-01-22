package admintravel.controller;

import static org.springframework.http.ResponseEntity.ok;

import admintravel.service.AdminTravelService;
import edu.fudan.common.entity.TravelInfo;
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

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/admintravelservice")
public class AdminTravelController {
  @Autowired AdminTravelService adminTravelService;

  private static final Logger logger = LoggerFactory.getLogger(AdminTravelController.class);

  @GetMapping(path = "/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ AdminTravel Service ] !";
  }

  @CrossOrigin(origins = "*")
  @GetMapping(path = "/admintravel")
  public HttpEntity getAllTravels(@RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[getAllTravels][Get all travels]");
    }
    return ok(adminTravelService.getAllTravels(headers));
  }

  @PostMapping(value = "/admintravel")
  public HttpEntity addTravel(@RequestBody TravelInfo request, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info(
          "[addTravel][Add travel][trip id: {}, train type name: {}, form station {} to station {},"
              + " login id: {}]",
          request.getTripId(),
          request.getTrainTypeName(),
          request.getStartStationName(),
          request.getStationsName(),
          request.getLoginId());
    }
    return ok(adminTravelService.addTravel(request, headers));
  }

  @PutMapping(value = "/admintravel")
  public HttpEntity updateTravel(
      @RequestBody TravelInfo request, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info(
          "[updateTravel][Update travel][trip id: {}, train type id: {}, form station {} to station"
              + " {}, login id: {}]",
          request.getTripId(),
          request.getTrainTypeName(),
          request.getStartStationName(),
          request.getStationsName(),
          request.getLoginId());
    }
    return ok(adminTravelService.updateTravel(request, headers));
  }

  @DeleteMapping(value = "/admintravel/{tripId}")
  public HttpEntity deleteTravel(@PathVariable String tripId, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[deleteTravel][Delete travel][trip id: {}]", tripId);
    }
    return ok(adminTravelService.deleteTravel(tripId, headers));
  }
}
