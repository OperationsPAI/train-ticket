package com.trainticket.platformkit.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {
    @Test
    void parsesPostgresqlUriIntoJdbcUrlAndCredentials() {
        DatabaseUrl parsed = DatabaseUrl.parse("postgresql://train%20user:pa%3Ass@postgres:5432/payment?sslmode=disable");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://postgres:5432/payment?sslmode=disable");
        assertThat(parsed.username()).isEqualTo("train user");
        assertThat(parsed.password()).isEqualTo("pa:ss");
    }

    @Test
    void rejectsNonPostgresUris() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://localhost/payment"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
