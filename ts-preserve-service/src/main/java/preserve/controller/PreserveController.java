package preserve.controller;

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
import preserve.service.PreserveService;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/preserveservice")
public class PreserveController {

  @Autowired private PreserveService preserveService;

  private static final Logger LOGGER = LoggerFactory.getLogger(PreserveController.class);

  @GetMapping(path = "/welcome")
  public String home() {
    return "Welcome to [ Preserve Service ] !";
  }

  @CrossOrigin(origins = "*")
  @PostMapping(value = "/preserve")
  public HttpEntity preserve(
      @RequestBody OrderTicketsInfo oti, @RequestHeader HttpHeaders headers) {
    PreserveController.LOGGER.info(
        "[preserve][Preserve Account order][from {} to {} at {}]",
        oti.getFrom(),
        oti.getTo(),
        oti.getDate());
    return ok(preserveService.preserve(oti, headers));
  }
}
