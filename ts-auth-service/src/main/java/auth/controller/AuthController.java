package auth.controller;

import auth.dto.AuthDto;
import auth.service.UserService;
import edu.fudan.common.util.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Authentication and authorization APIs")
public class AuthController {

  @Autowired private UserService userService;

  private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

  @Operation(summary = "Hello endpoint", description = "Simple hello endpoint for testing")
  @GetMapping("/hello")
  public String getHello() {
    return "hello";
  }

  @Operation(
      summary = "Create default auth user",
      description = "Creates a default role user during registration. Called by ts-user-service")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Auth user created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid auth data")
      })
  @PostMapping
  public HttpEntity<Response> createDefaultUser(
      @Parameter(description = "Authentication data for new user") @RequestBody AuthDto authDto) {
    if (logger.isInfoEnabled()) {
      logger.info(
          "[createDefaultUser][Create default auth user with authDto][AuthDto: {}]",
          authDto.toString());
    }
    userService.createDefaultAuthUser(authDto);
    Response response = new Response(1, "SUCCESS", authDto);
    return new ResponseEntity<>(response, HttpStatus.CREATED);
  }
}
