package auth.controller;

import auth.dto.BasicAuthDto;
import auth.entity.User;
import auth.exception.UserOperationException;
import auth.service.TokenService;
import auth.service.UserService;
import edu.fudan.common.util.Response;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/users")
public class UserController {

  @Autowired private UserService userService;

  @Autowired private TokenService tokenService;

  private static final Logger logger = LoggerFactory.getLogger(UserController.class);

  @GetMapping("/hello")
  public Object getHello() {
    return "Hello";
  }

  @PostMapping("/login")
  public ResponseEntity<Response> getToken(
      @RequestBody BasicAuthDto dao, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("Login request of username: {}", dao.getUsername());
    }
    try {
      Response<?> res = tokenService.getToken(dao, headers);
      return ResponseEntity.ok(res);
    } catch (UserOperationException e) {
      if (logger.isErrorEnabled()) {
        logger.error(
            "[getToken][tokenService.getToken error][UserOperationException, message: {}]",
            e.getMessage());
      }
      return ResponseEntity.ok(new Response<>(0, "get token error", null));
    }
  }

  @GetMapping("")
  public ResponseEntity<List<User>> getAllUser(@RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[getAllUser][Get all users]");
    }
    return ResponseEntity.ok().body(userService.getAllUser(headers));
  }

  @DeleteMapping("/{userId}")
  public ResponseEntity<Response> deleteUserById(
      @PathVariable String userId, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[deleteUserById][Delete user][userId: {}]", userId);
    }
    return ResponseEntity.ok(userService.deleteByUserId(userId, headers));
  }
}
