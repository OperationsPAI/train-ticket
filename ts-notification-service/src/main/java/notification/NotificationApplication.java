package notification;

import edu.fudan.common.config.RestTemplateConfig;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * @author fdse
 */
@OpenAPIDefinition(
    info = @Info(title = "Notification Service API", version = "1.0", description = "通知服务 API"))
@SpringBootApplication
@Import(RestTemplateConfig.class)
public class NotificationApplication {

  private NotificationApplication() {
    // Private constructor to prevent instantiation
  }

  public static void main(String[] args) {
    SpringApplication.run(NotificationApplication.class, args);
  }
}
