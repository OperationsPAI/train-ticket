package com.trainticket.financesettlement.infrastructure.persistence;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class FinanceSettlementReadiness {
    private final Optional<DataSource> dataSource;

    public FinanceSettlementReadiness(Optional<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isReady() {
        if (dataSource.isEmpty()) {
            return true;
        }
        try (Connection connection = dataSource.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            statement.execute("SELECT 1");
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
