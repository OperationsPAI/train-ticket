package com.trainticket.platformkit.idempotency;

import java.util.Optional;

public interface IdempotencyStore {
    Optional<StoredResponse> find(String key);

    StoredResponse saveIfAbsent(String key, StoredResponse response);

    record StoredResponse(String fingerprint, int status, String contentType, byte[] body) {
        public StoredResponse {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
