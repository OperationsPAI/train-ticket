package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.platformkit.persistence.MigrationRunner;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PaymentReadiness {
    private final Optional<MigrationRunner> migrationRunner;

    public PaymentReadiness(Optional<MigrationRunner> migrationRunner) {
        this.migrationRunner = migrationRunner;
    }

    public boolean isReady() {
        return migrationRunner.map(MigrationRunner::isReady).orElse(true);
    }
}
