package com.trainticket.platformkit.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class MigrationRunner {
    private static final Pattern MIGRATION_FILE = Pattern.compile("\\d{3}_[A-Za-z0-9_ -]+\\.sql");

    private final JdbcOperations jdbc;
    private final TransactionTemplate transactionTemplate;
    private volatile boolean ready;
    private volatile RuntimeException failure;

    public MigrationRunner(javax.sql.DataSource dataSource, org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(new JdbcTemplate(dataSource), new TransactionTemplate(transactionManager));
    }

    public MigrationRunner(JdbcOperations jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
    }

    public void run(Path migrationsDirectory) {
        try {
            List<Path> migrations = migrationFiles(migrationsDirectory);
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
                for (Path migration : migrations) {
                    applyIfNeeded(migration);
                }
            });
            ready = true;
            failure = null;
        } catch (RuntimeException exception) {
            ready = false;
            failure = exception;
            throw exception;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public RuntimeException failure() {
        return failure;
    }

    private void applyIfNeeded(Path migration) {
        String version = version(migration);
        Boolean alreadyApplied = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE version = ?)", Boolean.class, version);
        if (Boolean.TRUE.equals(alreadyApplied)) {
            return;
        }
        jdbc.execute(readSql(migration));
        jdbc.update("INSERT INTO schema_migrations(version) VALUES (?)", version);
    }

    private static List<Path> migrationFiles(Path migrationsDirectory) {
        if (migrationsDirectory == null || !Files.isDirectory(migrationsDirectory)) {
            return List.of();
        }
        try (var stream = Files.list(migrationsDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> MIGRATION_FILE.matcher(path.getFileName().toString()).matches())
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("migration directory could not be read", exception);
        }
    }

    private static String version(Path migration) {
        String fileName = migration.getFileName().toString();
        return fileName.substring(0, fileName.indexOf('_'));
    }

    private static String readSql(Path migration) {
        try {
            return Files.readString(migration, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("migration file could not be read", exception);
        }
    }
}
