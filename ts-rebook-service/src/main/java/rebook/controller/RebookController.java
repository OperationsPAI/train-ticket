package rebook.controller;

import static org.springframework.http.ResponseEntity.ok;

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
import rebook.entity.RebookInfo;
import rebook.service.RebookService;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/rebookservice")
public class RebookController {

  @Autowired RebookService service;

  private static final Logger LOGGER = LoggerFactory.getLogger(RebookController.class);

  @GetMapping(path = "/welcome")
  public String home() {
    return "Welcome to [ Rebook Service ] !";
  }

  @PostMapping(value = "/rebook/difference")
  public HttpEntity payDifference(
      @RequestBody RebookInfo info, @RequestHeader HttpHeaders headers) {
    RebookController.LOGGER.info("[payDifference][Pay difference][OrderId: {}]", info.getOrderId());
    return ok(service.payDifference(info, headers));
  }

  @PostMapping(value = "/rebook")
  public HttpEntity rebook(@RequestBody RebookInfo info, @RequestHeader HttpHeaders headers) {
    RebookController.LOGGER.info(
        "[rebook][Rebook][OrderId: {}, Old Trip Id: {}, New Trip Id: {}, Date: {}, Seat Type: {}]",
        info.getOrderId(),
        info.getOldTripId(),
        info.getTripId(),
        info.getDate(),
        info.getSeatType());
    return ok(service.rebook(info, headers));
  }
}
