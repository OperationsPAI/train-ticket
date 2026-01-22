package consignprice;

import edu.fudan.common.config.RestTemplateConfig;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            title = "Consign Price Service API",
            version = "1.0",
            description = "Train Ticket Consign Price Service - Manage consignment prices"))
public class ConsignPriceApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConsignPriceApplication.class);

  private ConsignPriceApplication() {
    // Private constructor to prevent instantiation
  }

  public static void main(String[] args) {
    ConsignPriceApplication.LOGGER.info(
        "[ConsignPriceApplication.main][launch date: {}]", new Date());
    SpringApplication.run(ConsignPriceApplication.class, args);
  }
}
