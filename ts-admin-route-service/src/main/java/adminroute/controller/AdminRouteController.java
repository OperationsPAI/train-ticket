package adminroute.controller;

import static org.springframework.http.ResponseEntity.ok;

import adminroute.service.AdminRouteService;
import edu.fudan.common.entity.RouteInfo;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/adminrouteservice")
public class AdminRouteController {

  @Autowired AdminRouteService adminRouteService;

  public static final Logger logger = LoggerFactory.getLogger(AdminRouteController.class);

  @GetMapping(path = "/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ AdminRoute Service ] !";
  }

  @CrossOrigin(origins = "*")
  @GetMapping(path = "/adminroute")
  public HttpEntity getAllRoutes(@RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[getAllRoutes][Get all routes request]");
    }
    return ok(adminRouteService.getAllRoutes(headers));
  }

  @PostMapping(value = "/adminroute")
  public HttpEntity addRoute(@RequestBody RouteInfo request, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info(
          "[addRoute][Create and modify route][route id: {}, from station {} to station {}]",
          request.getId(),
          request.getStartStation(),
          request.getEndStation());
    }
    return ok(adminRouteService.createAndModifyRoute(request, headers));
  }

  @DeleteMapping(value = "/adminroute/{routeId}")
  public HttpEntity deleteRoute(@PathVariable String routeId, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[deleteRoute][Delete route][route id: {}]", routeId);
    }
    return ok(adminRouteService.deleteRoute(routeId, headers));
  }
}
