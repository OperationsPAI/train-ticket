package adminuser.service;

import adminuser.dto.UserDto;
import edu.fudan.common.entity.User;
import edu.fudan.common.util.Response;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * @author fdse
 */
@Service
public class AdminUserServiceImpl implements AdminUserService {
  @Autowired private RestTemplate restTemplate;

  private static final Logger LOGGER = LoggerFactory.getLogger(AdminUserServiceImpl.class);
  //    @Value("${user-service.url}")
  //    String userServiceUrl;
  //    private final String userServiceIpUri = userServiceUrl + "/api/v1/userservice/users";

  private String getServiceUrl(String serviceName) {
    return "http://" + serviceName + ":8080";
  }

  @Override
  public Response getAllUsers(HttpHeaders headers) {
    HttpEntity requestEntity = new HttpEntity(headers);
    String userServiceUrl = getServiceUrl("ts-user-service");
    String userServiceIpUri = userServiceUrl + "/api/v1/userservice/users";
    ResponseEntity<Response<List<User>>> re =
        restTemplate.exchange(
            userServiceIpUri,
            HttpMethod.GET,
            requestEntity,
            new ParameterizedTypeReference<Response<List<User>>>() {});
    if (re.getBody() == null || re.getBody().getStatus() != 1) {
      AdminUserServiceImpl.LOGGER.error("[getAllUsers][receive response][Get All Users error]");
      return new Response<>(0, "get all users error", null);
    }
    AdminUserServiceImpl.LOGGER.info("[getAllUsers][Get All Users][success]");
    return re.getBody();
  }

  @Override
  public Response deleteUser(String userId, HttpHeaders headers) {
    HttpHeaders newHeaders = new HttpHeaders();
    String token = headers.getFirst(HttpHeaders.AUTHORIZATION);
    newHeaders.set(HttpHeaders.AUTHORIZATION, token);

    HttpEntity<Response> requestEntity = new HttpEntity<>(newHeaders);

    String userServiceUrl = getServiceUrl("ts-user-service");
    String userServiceIpUri = userServiceUrl + "/api/v1/userservice/users";
    ResponseEntity<Response> re =
        restTemplate.exchange(
            userServiceIpUri + "/" + userId, HttpMethod.DELETE, requestEntity, Response.class);
    if (re.getBody() == null || re.getBody().getStatus() != 1) {
      AdminUserServiceImpl.LOGGER.error(
          "[deleteUser][receive response][Delete user error][userId: {}]", userId);
      return new Response<>(0, "delete user error", null);
    }
    if (AdminUserServiceImpl.LOGGER.isInfoEnabled()) {
      AdminUserServiceImpl.LOGGER.info("[deleteUser][Delete user success][userId: {}]", userId);
    }
    return re.getBody();
  }

  @Override
  public Response updateUser(UserDto userDto, HttpHeaders headers) {
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("UPDATE USER: " + userDto.toString());
    }

    HttpHeaders newHeaders = new HttpHeaders();
    String token = headers.getFirst(HttpHeaders.AUTHORIZATION);
    newHeaders.set(HttpHeaders.AUTHORIZATION, token);

    HttpEntity requestEntity = new HttpEntity(userDto, newHeaders);
    String userServiceUrl = getServiceUrl("ts-user-service");
    String userServiceIpUri = userServiceUrl + "/api/v1/userservice/users";
    ResponseEntity<Response> re =
        restTemplate.exchange(userServiceIpUri, HttpMethod.PUT, requestEntity, Response.class);

    String userName = userDto.getUserName();
    if (re.getBody() == null || re.getBody().getStatus() != 1) {
      if (AdminUserServiceImpl.LOGGER.isErrorEnabled()) {
        AdminUserServiceImpl.LOGGER.error(
            "[updateUser][receive response][Update user error][userName: {}]", userName);
      }
      return new Response<>(0, "Update user error", null);
    }
    if (AdminUserServiceImpl.LOGGER.isInfoEnabled()) {
      AdminUserServiceImpl.LOGGER.info("[updateUser][Update user success][userName: {}]", userName);
    }
    return re.getBody();
  }

  @Override
  public Response addUser(UserDto userDto, HttpHeaders headers) {
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("[addUser][ADD USER INFO][UserDto: {}]", userDto.toString());
    }
    HttpEntity requestEntity = new HttpEntity(userDto, headers);
    String userServiceUrl = getServiceUrl("ts-user-service");
    String userServiceIpUri = userServiceUrl + "/api/v1/userservice/users";
    ResponseEntity<Response<User>> re =
        restTemplate.exchange(
            userServiceIpUri + "/register",
            HttpMethod.POST,
            requestEntity,
            new ParameterizedTypeReference<Response<User>>() {});

    String userName = userDto.getUserName();
    if (re.getBody() == null || re.getBody().getStatus() != 1) {
      if (AdminUserServiceImpl.LOGGER.isErrorEnabled()) {
        AdminUserServiceImpl.LOGGER.error(
            "[addUser][receive response][Add user error][userName: {}]", userName);
      }
      return new Response<>(0, "Add user error", null);
    }
    if (AdminUserServiceImpl.LOGGER.isInfoEnabled()) {
      AdminUserServiceImpl.LOGGER.info("[addUser][Add user success][userName: {}]", userName);
    }
    return re.getBody();
  }
}
