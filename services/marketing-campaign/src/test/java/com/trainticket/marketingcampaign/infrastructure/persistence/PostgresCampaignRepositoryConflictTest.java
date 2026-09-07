package com.trainticket.marketingcampaign.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.marketingcampaign.application.DuplicateBusinessKeyException;
import com.trainticket.marketingcampaign.domain.Campaign;
import com.trainticket.marketingcampaign.domain.CampaignWindow;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.OutboxAppender;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * A duplicate externalKey and a lost-update race both answer 409, but they are different failures and must stay
 * distinguishable. Previously a bare {@code ON CONFLICT DO NOTHING} swallowed the externalKey unique violation,
 * returned zero affected rows, and the caller was told "snapshot version conflict for &lt;freshly minted id&gt;" —
 * an id that had just been generated locally and could not possibly have raced with anything.
 */
class PostgresCampaignRepositoryConflictTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void campaignInsertTargetsThePrimaryKeySoBusinessUniqueViolationsAreNotSwallowed() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PostgresCampaignRepository repository = repository(jdbc);

        repository.saveCampaign(draft("mc-1", "summer-2026"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(1)).update(sql.capture(), any(Object[].class));
        List<String> campaignInserts = sql.getAllValues().stream()
            .filter(statement -> statement.startsWith("INSERT INTO campaigns"))
            .toList();

        assertThat(campaignInserts).isNotEmpty();
        assertThat(campaignInserts).allMatch(statement -> statement.contains("ON CONFLICT (campaign_id) DO NOTHING"));
    }

    @Test
    void duplicateExternalKeyRaisesDuplicateBusinessKeyNamingTheOffendingKey() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(startsWith("INSERT INTO campaigns"), any(Object[].class)))
            .thenThrow(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"campaigns_external_key_idx\""));
        PostgresCampaignRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.saveCampaign(draft("mc-2", "summer-2026")))
            .isInstanceOf(DuplicateBusinessKeyException.class)
            .hasMessageContaining("externalKey")
            .hasMessageContaining("summer-2026")
            .hasMessageNotContaining("snapshot version conflict");
    }

    @Test
    void aTakenAggregateIdStillRaisesOptimisticConcurrency() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(startsWith("INSERT INTO campaigns"), any(Object[].class))).thenReturn(0);
        PostgresCampaignRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.saveCampaign(draft("mc-3", "autumn-2026")))
            .isInstanceOf(OptimisticConcurrencyException.class)
            .hasMessageContaining("snapshot version conflict");
    }

    @Test
    void staleUpdateStillRaisesOptimisticConcurrency() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(startsWith("UPDATE campaigns"), any(Object[].class))).thenReturn(0);
        PostgresCampaignRepository repository = repository(jdbc);
        Campaign submitted = draft("mc-4", "winter-2026").submitForReview(Instant.parse("2026-01-02T00:00:00Z"));

        assertThatThrownBy(() -> repository.saveCampaign(submitted))
            .isInstanceOf(OptimisticConcurrencyException.class)
            .hasMessageContaining("snapshot version conflict");
    }

    private PostgresCampaignRepository repository(JdbcOperations jdbc) {
        return new PostgresCampaignRepository(jdbc, objectMapper, new OutboxAppender(jdbc, objectMapper));
    }

    private static Campaign draft(String campaignId, String externalKey) {
        return Campaign.draft(
            campaignId,
            externalKey,
            "Campaign " + externalKey,
            new CampaignWindow(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")),
            Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
