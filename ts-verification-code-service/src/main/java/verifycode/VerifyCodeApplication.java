package verifycode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import edu.fudan.common.config.RestTemplateConfig;
import org.springframework.context.annotation.Import;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * @author fdse
 */
@OpenAPIDefinition(info = @Info(
    title = "Verify Code Service API",
    version = "1.0",
    description = "验证码服务 API"
))
@SpringBootApplication
@Import(RestTemplateConfig.class)
public class VerifyCodeApplication {

    private VerifyCodeApplication() {
        // Private constructor to prevent instantiation
    }

    public static void main(String[] args) {
        SpringApplication.run(VerifyCodeApplication.class, args);
    }
}
