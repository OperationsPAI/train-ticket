package plan.controller;

import static org.springframework.http.ResponseEntity.ok;

import edu.fudan.common.entity.RoutePlanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import plan.service.RoutePlanService;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/routeplanservice")
public class RoutePlanController {

  @Autowired private RoutePlanService routePlanService;
  private static final Logger LOGGER = LoggerFactory.getLogger(RoutePlanController.class);

  @GetMapping(path = "/welcome")
  public String home() {
    return "Welcome to [ RoutePlan Service ] !";
  }

  @PostMapping(value = "/routePlan/cheapestRoute")
  public HttpEntity getCheapestRoutes(
      @RequestBody RoutePlanInfo info, @RequestHeader HttpHeaders headers) {
    RoutePlanController.LOGGER.info(
        "[searchCheapestResult][Get Cheapest Routes][From: {}, To: {}, Num: {}, Date: {}]",
        info.getStartStation(),
        info.getEndStation(),
        +info.getNum(),
        info.getTravelDate());
    return ok(routePlanService.searchCheapestResult(info, headers));
  }

  @PostMapping(value = "/routePlan/quickestRoute")
  public HttpEntity getQuickestRoutes(
      @RequestBody RoutePlanInfo info, @RequestHeader HttpHeaders headers) {
    RoutePlanController.LOGGER.info(
        "[searchQuickestResult][Get Quickest Routes][From: {}, To: {}, Num: {}, Date: {}]",
        info.getStartStation(),
        info.getEndStation(),
        info.getNum(),
        info.getTravelDate());
    return ok(routePlanService.searchQuickestResult(info, headers));
  }

  @PostMapping(value = "/routePlan/minStopStations")
  public HttpEntity getMinStopStations(
      @RequestBody RoutePlanInfo info, @RequestHeader HttpHeaders headers) {
    RoutePlanController.LOGGER.info(
        "[searchMinStopStations][Get Min Stop Stations][From: {}, To: {}, Num: {}, Date: {}]",
        info.getStartStation(),
        info.getEndStation(),
        info.getNum(),
        info.getTravelDate());
    return ok(routePlanService.searchMinStopStations(info, headers));
  }
}
