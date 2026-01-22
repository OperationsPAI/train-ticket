package delivery;

import edu.fudan.common.config.RestTemplateConfig;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * @author humbertzhang
 */
@SpringBootApplication
@Import(RestTemplateConfig.class)
@OpenAPIDefinition(
    info =
        @Info(
            title = "Delivery Service API",
            version = "1.0",
            description = "Delivery Service API"))
public class DeliveryApplication {

  private DeliveryApplication() {
    // Private constructor to prevent instantiation
  }

  public static void main(String[] args) {
    SpringApplication.run(DeliveryApplication.class, args);
  }
}
