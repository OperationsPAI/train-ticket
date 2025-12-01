package auth.security.jwt;

import auth.constant.InfoConstant;
import auth.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * @author fdse
 */
@Component
public class JWTProvider {

    // Must match the secret key in ts-common JWTUtil
    private String secretKey = "secretsecretsecretsecretsecretsecret";

    private long validityInMilliseconds = 3600000;

    private SecretKey key;

    @PostConstruct
    protected void init() {
        key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public String createToken(User user) {
        Date now = new Date();
        Date validate = new Date(now.getTime() + validityInMilliseconds);

        return Jwts.builder()
                .subject(user.getUsername())
                .claim(InfoConstant.ROLES, user.getRoles())
                .claim(InfoConstant.ID, user.getUserId())
                .issuedAt(now)
                .expiration(validate)
                .signWith(key)
                .compact();
    }
}