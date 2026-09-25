package com.trainticket.walletpromotion.metrics;

import java.util.List;

/**
 * Reads the current wallet balance levels from durable state.
 *
 * <p>A gauge callback runs on the export interval, so this must be a read of the
 * persisted snapshots rather than any counter this process accumulated: a
 * restart has to reconstruct the same levels.
 */
@FunctionalInterface
public interface WalletBalanceSnapshotProvider {
    List<WalletBalanceSample> snapshot();
}
