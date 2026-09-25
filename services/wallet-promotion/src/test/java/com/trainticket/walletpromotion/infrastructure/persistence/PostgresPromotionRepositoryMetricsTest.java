package com.trainticket.walletpromotion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.metrics.WalletBalanceSample;
import com.trainticket.walletpromotion.metrics.WalletBalanceState;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

class PostgresPromotionRepositoryMetricsTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void walletBalanceSnapshotAggregatesEachDurableTablePerCurrency() throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(contains("jsonb_array_elements"), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getString("currency")).thenReturn("usd");
            when(row.getLong("available")).thenReturn(80L);
            when(row.getLong("reserved")).thenReturn(0L);
            when(row.getLong("redeemed")).thenReturn(20L);
            mapper.mapRow(row, 0);
            return List.of();
        });
        when(jdbc.query(contains("'EXPIRED'"), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getString("currency")).thenReturn("USD");
            when(row.getLong("expired")).thenReturn(40L);
            mapper.mapRow(row, 0);
            return List.of();
        });
        when(jdbc.query(contains("redemption_reversals"), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getString("currency")).thenReturn("USD");
            when(row.getLong("reversed")).thenReturn(10L);
            mapper.mapRow(row, 0);
            return List.of();
        });

        List<WalletBalanceSample> samples = repository(jdbc).walletBalanceSnapshot();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class));
        // The sums happen in SQL, not by loading every wallet into the JVM.
        assertThat(sql.getAllValues().get(0)).contains("wallet_account_snapshots", "jsonb_array_elements", "SUM(", "GROUP BY 1");
        assertThat(sql.getAllValues().get(1)).contains("promotion_instrument_snapshots", "'EXPIRED'", "GROUP BY 1");
        assertThat(sql.getAllValues().get(2)).contains("redemption_reversals", "GROUP BY 1");

        assertThat(samples).containsExactly(
            new WalletBalanceSample(WalletBalanceState.AVAILABLE, "USD", 80),
            new WalletBalanceSample(WalletBalanceState.RESERVED, "USD", 0),
            new WalletBalanceSample(WalletBalanceState.REDEEMED, "USD", 20),
            new WalletBalanceSample(WalletBalanceState.EXPIRED, "USD", 40),
            new WalletBalanceSample(WalletBalanceState.REVERSED, "USD", 10)
        );
    }

    private PostgresPromotionRepository repository(JdbcOperations jdbc) {
        return new PostgresPromotionRepository(jdbc, objectMapper, new OutboxAppender(jdbc, objectMapper));
    }
}
