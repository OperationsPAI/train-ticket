package com.trainticket.groupbooking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trainticket.platformkit.persistence.MigrationRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression guard for the wiring omission where group-booking was the only one
 * of the ten Java services that never referenced
 * {@link com.trainticket.platformkit.persistence.MigrationRunner}: the migration
 * shipped in the image and was never applied, leaving {@code group_booking} with
 * zero tables while {@code /readyz} still answered {@code ready}.
 *
 * <p><strong>What these tests prove and what they do not.</strong> There is no
 * local Postgres in this environment, so nothing here executes SQL. The runner
 * is driven against a Mockito {@link JdbcOperations} double, which means these
 * tests prove:
 * <ul>
 *   <li>the real on-disk {@code migrations/001_group_booking.sql} is discovered
 *       by the real {@link MigrationRunner} through the same path the production
 *       bean resolves, and its text is handed to {@code jdbc.execute};</li>
 *   <li>the SQL text declares the tables the code and its platform-kit
 *       dependencies address, and does not declare the orphan table it used to;</li>
 *   <li>the applied-version bookkeeping makes a second run a no-op.</li>
 * </ul>
 * They do <strong>not</strong> prove the SQL is valid Postgres, that it executes
 * without error, or that the resulting tables have the right shape. Only a real
 * database (or Testcontainers) can establish that; the SQL-level assertions here
 * are textual.
 */
class GroupBookingMigrationRunnerTest {
    private static final Path MIGRATIONS = Path.of("migrations");
    private static final String MIGRATION_FILE = "001_group_booking.sql";

    /**
     * The failing-before test. The runner is pointed at the real migrations
     * directory and must actually read and execute the checked-in file. Before
     * the wiring existed nothing in the service ever reached this code path.
     */
    @Test
    void runAppliesTheCheckedInGroupBookingMigration() throws IOException {
        JdbcOperations jdbc = notYetAppliedJdbc();

        new MigrationRunner(jdbc, immediateTransactionTemplate()).run(MIGRATIONS);

        // schema_migrations is created, then the migration body is executed.
        ArgumentCaptor<String> executed = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).execute(executed.capture());
        assertThat(executed.getAllValues())
            .anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS schema_migrations"));
        assertThat(executed.getAllValues())
            .contains(Files.readString(MIGRATIONS.resolve(MIGRATION_FILE), StandardCharsets.UTF_8));

        // ...and version 001 is recorded so the next boot skips it.
        verify(jdbc).update("INSERT INTO schema_migrations(version) VALUES (?)", "001");
    }

    /** A restart must not re-run or fail: the runner skips recorded versions. */
    @Test
    void rerunIsIdempotentOnceVersionIsRecorded() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        MigrationRunner runner = new MigrationRunner(jdbc, immediateTransactionTemplate());

        runner.run(MIGRATIONS);

        assertThat(runner.isReady()).isTrue();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).execute(org.mockito.Mockito.contains("CREATE TABLE IF NOT EXISTS group_bookings"));
    }

    /**
     * The migration must create every table the service and the platform-kit
     * primitives it inherits actually address. {@code outbox},
     * {@code processed_events} and {@code idempotency_records} are the names
     * hard-coded in OutboxAppender/OutboxRelay, ProcessedEventStore and
     * DbIdempotencyStore respectively, and all nine sibling migrations create
     * them. Textual assertion only -- see the class comment.
     */
    @Test
    void migrationDeclaresEveryTableTheCodeAddresses() throws IOException {
        Set<String> tables = createdTables(migrationSql());

        assertThat(tables).contains(
            "group_bookings",
            "group_members",
            "outbox",
            "processed_events",
            "idempotency_records"
        );
    }

    /**
     * {@code group_booking_outbox} was the table the migration used to declare.
     * No reader or writer anywhere in the repository referenced it, and its
     * columns did not match the outbox contract OutboxRelay polls, so it could
     * never have been drained. Guard against it coming back.
     */
    @Test
    void migrationDoesNotDeclareAnOrphanOutboxTable() throws IOException {
        assertThat(createdTables(migrationSql())).doesNotContain("group_booking_outbox");
    }

    /**
     * The roster rule in GroupBooking.activeTravelerExists() is ACTIVE-scoped: a
     * traveler whose membership was CANCELLED may be re-added. An absolute
     * UNIQUE (group_booking_id, traveler_ref) would reject that legal re-add, so
     * the uniqueness must be a partial index restricted to ACTIVE rows.
     */
    @Test
    void travelerUniquenessIsScopedToActiveMembersOnly() throws IOException {
        String sql = migrationSql();
        String groupMembers = createTableBody(sql, "group_members");

        assertThat(groupMembers)
            .as("an absolute UNIQUE would reject re-adding a previously cancelled traveler")
            .doesNotContain("unique");
        assertThat(normalize(sql)).contains(
            "create unique index if not exists idx_group_members_active_traveler "
                + "on group_members(group_booking_id, traveler_ref) where status = 'active'");
    }

    /** Columns the aggregate rehydrates from must all exist in group_bookings. */
    @Test
    void groupBookingsCarriesEveryFieldTheAggregateRehydrates() throws IOException {
        String body = createTableBody(migrationSql(), "group_bookings");

        // GroupBooking.rehydrate(groupBookingId, organizerRef, segmentRefs,
        // targetTravelerCount, fare, status, capacityHoldId, cancellationReason, members)
        assertThat(body).contains(
            "group_booking_id",
            "organizer_ref",
            "segment_refs",
            "target_traveler_count",
            "status",
            "capacity_hold_id",
            "cancellation_reason"
        );
        // GroupFare(currency, minorUnits, discountBasisPoints, negotiationRef)
        assertThat(body).contains(
            "fare_currency",
            "fare_minor_units",
            "discount_basis_points",
            "negotiation_ref"
        );
    }

    /** Columns GroupMember.rehydrate needs must all exist in group_members. */
    @Test
    void groupMembersCarriesEveryFieldTheMemberRehydrates() throws IOException {
        String body = createTableBody(migrationSql(), "group_members");

        // GroupMember.rehydrate(memberId, travelerRef, maskedDocumentRef, status, addedAt)
        assertThat(body).contains(
            "member_id",
            "group_booking_id",
            "traveler_ref",
            "masked_document_ref",
            "status",
            "added_at"
        );
    }

    /** The outbox must match the column contract OutboxRelay polls and updates. */
    @Test
    void outboxMatchesThePlatformKitRelayContract() throws IOException {
        String body = createTableBody(migrationSql(), "outbox");

        // OutboxRelay: SELECT seq, stream, envelope FROM outbox WHERE published_at IS NULL
        // OutboxAppender: INSERT INTO outbox(event_id, stream, envelope)
        assertThat(body).contains("seq", "event_id", "stream", "envelope", "published_at");
    }

    /**
     * The bean resolves the directory the same way in the container and under
     * Maven: the relative default resolves against the working directory, which
     * is {@code /app} in the image (migrations copied to {@code /app/migrations})
     * and the module directory in tests. {@code MIGRATIONS_DIR} overrides it.
     */
    @Test
    void migrationsDirectoryDefaultsToRelativeMigrationsAndHonoursTheEnvOverride() {
        assertThat(GroupBookingPersistenceConfiguration.migrationsDirectory(null))
            .isEqualTo(Path.of("migrations"));
        assertThat(GroupBookingPersistenceConfiguration.migrationsDirectory("  "))
            .isEqualTo(Path.of("migrations"));
        assertThat(GroupBookingPersistenceConfiguration.migrationsDirectory("/app/migrations"))
            .isEqualTo(Path.of("/app/migrations"));
    }

    /** The default the bean uses must actually contain the checked-in migration. */
    @Test
    void defaultMigrationsDirectoryResolvesToTheCheckedInFile() {
        Path resolved = GroupBookingPersistenceConfiguration.migrationsDirectory(null);

        assertThat(Files.isDirectory(resolved))
            .as("MigrationRunner silently applies nothing when the directory is missing")
            .isTrue();
        assertThat(Files.isRegularFile(resolved.resolve(MIGRATION_FILE))).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    private static String migrationSql() throws IOException {
        return Files.readString(MIGRATIONS.resolve(MIGRATION_FILE), StandardCharsets.UTF_8);
    }

    private static JdbcOperations notYetAppliedJdbc() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        return jdbc;
    }

    /**
     * MigrationRunner takes a concrete TransactionTemplate, so give it one whose
     * transaction manager is a no-op: the callback runs inline, no database.
     */
    private static TransactionTemplate immediateTransactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(null);
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }

    /** Strip comments and collapse whitespace so assertions ignore formatting. */
    private static String normalize(String sql) {
        return sql.replaceAll("(?m)--[^\\n]*", " ")
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT)
            .trim();
    }

    private static Set<String> createdTables(String sql) {
        Matcher matcher = Pattern
            .compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z0-9_]+)")
            .matcher(normalize(sql));
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return Set.copyOf(tables);
    }

    /** The parenthesised column list of one CREATE TABLE, comments removed. */
    private static String createTableBody(String sql, String table) {
        String normalized = normalize(sql);
        Matcher matcher = Pattern
            .compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?" + Pattern.quote(table) + "\\s*\\(")
            .matcher(normalized);
        assertThat(matcher.find()).as("migration must create %s", table).isTrue();

        int depth = 1;
        int index = matcher.end();
        StringBuilder body = new StringBuilder();
        while (index < normalized.length() && depth > 0) {
            char character = normalized.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            body.append(character);
            index++;
        }
        assertThat(depth).as("unbalanced parentheses in %s", table).isZero();
        // normalize() already lower-cased; keep it that way so callers assert
        // lower-case identifiers consistently.
        return body.toString();
    }
}
