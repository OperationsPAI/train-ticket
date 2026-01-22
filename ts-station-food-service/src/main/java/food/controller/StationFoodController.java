package food.controller;

import static org.springframework.http.ResponseEntity.ok;

import food.service.StationFoodService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stationfoodservice")
public class StationFoodController {

  @Autowired StationFoodService stationFoodService;

  private static final Logger LOGGER = LoggerFactory.getLogger(StationFoodController.class);

  @GetMapping(path = "/stationfoodstores/welcome")
  public String home() {
    return "Welcome to [ Food store Service ] !";
  }

  @CrossOrigin(origins = "*")
  @GetMapping("/stationfoodstores")
  public HttpEntity getAllFoodStores(@RequestHeader HttpHeaders headers) {
    StationFoodController.LOGGER.info("[Food Map Service][Get All FoodStores]");
    return ok(stationFoodService.listFoodStores(headers));
  }

  @CrossOrigin(origins = "*")
  @GetMapping("/stationfoodstores/{stationName}")
  public HttpEntity getFoodStoresOfStation(
      @PathVariable String stationName, @RequestHeader HttpHeaders headers) {
    StationFoodController.LOGGER.info("[Food Map Service][Get FoodStores By StationName]");
    return ok(stationFoodService.listFoodStoresByStationName(stationName, headers));
  }

  @CrossOrigin(origins = "*")
  @PostMapping("/stationfoodstores")
  public HttpEntity getFoodStoresByStationNames(@RequestBody List<String> stationNameList) {
    StationFoodController.LOGGER.info("[Food Map Service][Get FoodStores By StationNames]");
    return ok(stationFoodService.getFoodStoresByStationNames(stationNameList));
  }

  @GetMapping("/stationfoodstores/bystoreid/{stationFoodStoreId}")
  public HttpEntity getFoodListByStationFoodStoreId(
      @PathVariable String stationFoodStoreId, @RequestHeader HttpHeaders headers) {
    StationFoodController.LOGGER.info("[Food Map Service][Get Foodlist By stationFoodStoreId]");
    return ok(stationFoodService.getStaionFoodStoreById(stationFoodStoreId));
  }
}
