package com.anti.houseepg.application.config.port;

import com.anti.houseepg.domain.config.model.SourceOrder;

/**
 * Durability of the one piece of user state: the source order, kept in the YAML file that declares the
 * sources (ADR-0005).
 *
 * <p>Persistence is best-effort by decision: a write that fails is logged by the adapter and reported
 * through {@link PersistResult}, never thrown, because the reorder has already taken effect in the
 * running merge and reporting an error would suggest otherwise.
 */
public interface SourceOrderStore {

    PersistResult persist(SourceOrder order);

    /**
     * @param persisted whether the order now survives a restart
     * @param detail why it does not, when it does not; never carries credentials
     */
    record PersistResult(boolean persisted, String detail) {

        public PersistResult {
            detail = detail == null ? "" : detail;
        }

        public static PersistResult saved() {
            return new PersistResult(true, "");
        }

        /** The order applies to the running merge but a restart would not see it. */
        public static PersistResult notSaved(String detail) {
            return new PersistResult(false, detail);
        }
    }
}
