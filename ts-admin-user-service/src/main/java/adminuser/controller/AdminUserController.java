package adminuser.controller;

import static org.springframework.http.ResponseEntity.ok;

import adminuser.dto.UserDto;
import adminuser.service.AdminUserService;
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
@RequestMapping("/api/v1/adminuserservice/users")
public class AdminUserController {

  @Autowired AdminUserService adminUserService;
  private static final Logger logger = LoggerFactory.getLogger(AdminUserController.class);

  @GetMapping(path = "/welcome")
  public String home(@RequestHeader HttpHeaders headers) {
    return "Welcome to [ AdminUser Service ] !";
  }

  @CrossOrigin(origins = "*")
  @GetMapping
  public HttpEntity getAllUsers(@RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[getAllUsers][Get all users]");
    }
    return ok(adminUserService.getAllUsers(headers));
  }

  @PutMapping
  public HttpEntity updateUser(@RequestBody UserDto userDto, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[updateUser][Update User][userName: {}]", userDto.getUserName());
    }
    return ok(adminUserService.updateUser(userDto, headers));
  }

  @PostMapping
  public HttpEntity addUser(@RequestBody UserDto userDto, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[addUser][Add user][userName: {}]", userDto.getUserName());
    }
    return ok(adminUserService.addUser(userDto, headers));
  }

  @DeleteMapping(value = "/{userId}")
  public HttpEntity deleteUser(@PathVariable String userId, @RequestHeader HttpHeaders headers) {
    if (logger.isInfoEnabled()) {
      logger.info("[deleteUser][Delete user][userId: {}]", userId);
    }
    return ok(adminUserService.deleteUser(userId, headers));
  }
}
