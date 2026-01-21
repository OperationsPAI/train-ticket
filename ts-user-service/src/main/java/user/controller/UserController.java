package user.controller;

import edu.fudan.common.util.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import user.dto.UserDto;
import user.service.UserService;

import static org.springframework.http.ResponseEntity.ok;

/**
 * @author fdse
 */
@RestController
@RequestMapping("/api/v1/userservice/users")
@Tag(name = "User", description = "User management APIs")
public class UserController {

    @Autowired
    private UserService userService;

    private static final Logger LOGGER = LoggerFactory.getLogger(UserController.class);

    @Operation(summary = "Test endpoint", description = "Simple hello endpoint for testing")
    @GetMapping("/hello")
    public String testHello() {
        return "Hello";
    }

    @Operation(summary = "Get all users", description = "Retrieve all users in the system")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved all users")
    })
    @GetMapping
    public ResponseEntity<Response> getAllUser(@RequestHeader HttpHeaders headers) {
        UserController.LOGGER.info("[getAllUser][Get all user]");
        return ok(userService.getAllUsers(headers));
    }

    @Operation(summary = "Get user by username", description = "Retrieve a user by their username")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User found"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{userName}")
    public ResponseEntity<Response> getUserByUserName(
            @Parameter(description = "Username to search for") @PathVariable String userName,
            @RequestHeader HttpHeaders headers) {
        UserController.LOGGER.info("[getUserByUserName][Get user by user name][UserName: {}]",userName);
        return ok(userService.findByUserName(userName, headers));
    }

    @Operation(summary = "Get user by ID", description = "Retrieve a user by their user ID")
    @GetMapping("/id/{userId}")
    public ResponseEntity<Response> getUserByUserId(
            @Parameter(description = "User ID") @PathVariable String userId,
            @RequestHeader HttpHeaders headers) {
        UserController.LOGGER.info("[getUserByUserId][Get user by user id][UserId: {}]",userId);
        return ok(userService.findByUserId(userId, headers));
    }

    @Operation(summary = "Register new user", description = "Register a new user in the system")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "User registered successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid user data or user already exists")
    })
    @PostMapping("/register")
    public ResponseEntity<Response> registerUser(
            @Parameter(description = "User registration data") @RequestBody UserDto userDto,
            @RequestHeader HttpHeaders headers) {
        UserController.LOGGER.info("[registerUser][Register user][UserName: {}]",userDto.getUserName());
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.saveUser(userDto, headers));
    }

    @Operation(summary = "Delete user", description = "Delete a user by their ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User deleted successfully"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @DeleteMapping("/{userId}")
    public ResponseEntity<Response> deleteUserById(
            @Parameter(description = "User ID to delete") @PathVariable String userId,
            @RequestHeader HttpHeaders headers) {
        UserController.LOGGER.info("[deleteUserById][Delete user][UserId: {}]",userId);
        return ok(userService.deleteUser(userId, headers));
    }

    @Operation(summary = "Update user", description = "Update an existing user's information")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User updated successfully"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PutMapping
    public ResponseEntity<Response> updateUser(
            @Parameter(description = "Updated user data") @RequestBody UserDto user,
            @RequestHeader HttpHeaders headers) {
        UserController.LOGGER.info("[updateUser][Update user][UserId: {}]",user.getUserId());
        return ok(userService.updateUser(user, headers));
    }

}
