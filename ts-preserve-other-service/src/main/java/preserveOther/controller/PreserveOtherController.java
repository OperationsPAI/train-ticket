package preserveOther.controller;

import static org.springframework.http.ResponseEntity.ok;

import edu.fudan.common.entity.OrderTicketsInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import preserveOther.service.PreserveOtherService;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/preserveotherservice")
public class PreserveOtherController {

  @Autowired private PreserveOtherService preserveService;

  private static final Logger LOGGER = LoggerFactory.getLogger(PreserveOtherController.class);

  @GetMapping(path = "/welcome")
  public String home() {
    return "Welcome to [ PreserveOther Service ] !";
  }

  @CrossOrigin(origins = "*")
  @PostMapping(value = "/preserveOther")
  public HttpEntity preserve(
      @RequestBody OrderTicketsInfo oti, @RequestHeader HttpHeaders headers) {
    PreserveOtherController.LOGGER.info(
        "[preserve][Preserve Account order][from {} to {} at {}]",
        oti.getFrom(),
        oti.getTo(),
        oti.getDate());
    return ok(preserveService.preserve(oti, headers));
  }
}
