package adminuser;

import edu.fudan.common.config.RestTemplateConfig;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @author fdse
 */
@SpringBootApplication
@Import(RestTemplateConfig.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableAsync
@IntegrationComponentScan
@OpenAPIDefinition(
    info =
        @Info(
            title = "Admin User Service API",
            version = "1.0",
            description = "Admin User Service API"))
public class AdminUserApplication {

  private AdminUserApplication() {
    // Private constructor to prevent instantiation
  }

  public static void main(String[] args) {
    SpringApplication.run(AdminUserApplication.class, args);
  }
}
