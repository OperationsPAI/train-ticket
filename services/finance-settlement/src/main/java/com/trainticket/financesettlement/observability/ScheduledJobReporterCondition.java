package com.trainticket.financesettlement.observability;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether the process can report scheduled-job ages at all.
 *
 * <p>A process with no database has no durable run records to read, so the
 * reporter has nothing to publish and is not registered. This is deliberately a
 * plain {@link Condition} reading the same expression the persistence
 * configuration uses, rather than a `@ConditionalOnBean(DataSource.class)`: that
 * condition is only reliable in auto-configuration, and using it on a
 * component-scanned class is what silently skipped the settlement scheduler.
 */
public class ScheduledJobReporterCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String databaseUrl = context.getEnvironment().getProperty("DATABASE_URL", "");
        return databaseUrl != null && !databaseUrl.isBlank();
    }
}
