package com.trainticket.platformkit.idempotency;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IdempotencyStore {
    Optional<StoredResponse> find(String key);

    StoredResponse saveIfAbsent(String key, StoredResponse response);

    record StoredResponse(String fingerprint, int status, String contentType, Map<String, List<String>> headers, byte[] body) {
        public StoredResponse {
            headers = headers == null ? Map.of() : headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue())
                ));
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
