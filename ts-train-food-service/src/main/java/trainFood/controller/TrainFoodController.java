package trainFood.controller;

import static org.springframework.http.ResponseEntity.ok;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import trainFood.service.TrainFoodService;

@RestController
@RequestMapping("/api/v1/trainfoodservice")
public class TrainFoodController {

  @Autowired TrainFoodService trainFoodService;

  private static final Logger LOGGER = LoggerFactory.getLogger(TrainFoodController.class);

  @GetMapping(path = "/trainfoods/welcome")
  public String home() {
    return "Welcome to [ Train Food Service ] !";
  }

  @CrossOrigin(origins = "*")
  @GetMapping("/trainfoods")
  public HttpEntity getAllTrainFood(@RequestHeader HttpHeaders headers) {
    TrainFoodController.LOGGER.info("[Food Map Service][Get All TrainFoods]");
    return ok(trainFoodService.listTrainFood(headers));
  }

  @CrossOrigin(origins = "*")
  @GetMapping("/trainfoods/{tripId}")
  public HttpEntity getTrainFoodOfTrip(
      @PathVariable String tripId, @RequestHeader HttpHeaders headers) {
    TrainFoodController.LOGGER.info("[Food Map Service][Get TrainFoods By TripId]");
    return ok(trainFoodService.listTrainFoodByTripId(tripId, headers));
  }
}
