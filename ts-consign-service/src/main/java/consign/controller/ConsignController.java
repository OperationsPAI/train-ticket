package consign.controller;

import static org.springframework.http.ResponseEntity.ok;

import consign.entity.Consign;
import consign.service.ConsignService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/api/v1/consignservice")
public class ConsignController {

  @Autowired ConsignService service;

  private static final Logger logger = LoggerFactory.getLogger(ConsignController.class);

  @GetMapping(path = "/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ Consign Service ] !";
  }

  @PostMapping(value = "/consigns")
  public HttpEntity insertConsign(
      @RequestBody Consign request, @RequestHeader HttpHeaders headers) {
    logger.info("[insertConsign][Insert consign record][id:{}]", request.getId());
    return ok(service.insertConsignRecord(request, headers));
  }

  @PutMapping(value = "/consigns")
  public HttpEntity updateConsign(
      @RequestBody Consign request, @RequestHeader HttpHeaders headers) {
    logger.info("[updateConsign][Update consign record][id: {}]", request.getId());
    return ok(service.updateConsignRecord(request, headers));
  }

  @GetMapping(value = "/consigns/account/{id}")
  public HttpEntity findByAccountId(@PathVariable String id, @RequestHeader HttpHeaders headers) {
    logger.info("[findByAccountId][Find consign by account id][id: {}]", id);
    UUID newid = UUID.fromString(id);
    return ok(service.queryByAccountId(newid, headers));
  }

  @GetMapping(value = "/consigns/order/{id}")
  public HttpEntity findByOrderId(@PathVariable String id, @RequestHeader HttpHeaders headers) {
    logger.info("[findByOrderId][Find consign by order id][id: {}]", id);
    UUID newid = UUID.fromString(id);
    return ok(service.queryByOrderId(newid, headers));
  }

  @GetMapping(value = "/consigns/{consignee}")
  public HttpEntity findByConsignee(
      @PathVariable String consignee, @RequestHeader HttpHeaders headers) {
    logger.info("[findByConsignee][Find consign by consignee][consignee: {}]", consignee);
    return ok(service.queryByConsignee(consignee, headers));
  }
}
