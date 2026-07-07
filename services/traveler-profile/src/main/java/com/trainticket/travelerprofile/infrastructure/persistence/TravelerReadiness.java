package com.trainticket.travelerprofile.infrastructure.persistence;

import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TravelerReadiness {
    private final Optional<DataSource> dataSource;
    public TravelerReadiness(Optional<DataSource> dataSource) { this.dataSource = dataSource; }
    public boolean isReady() {
        return dataSource.map(ds -> {
            try { new JdbcTemplate(ds).queryForObject("SELECT 1", Integer.class); return true; }
            catch (RuntimeException exception) { return false; }
        }).orElse(true);
    }
}
