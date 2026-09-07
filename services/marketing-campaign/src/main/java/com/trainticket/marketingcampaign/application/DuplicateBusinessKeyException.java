package com.trainticket.marketingcampaign.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

/**
 * A unique <em>business</em> key was already taken by another aggregate instance.
 *
 * <p>This is deliberately distinct from
 * {@link com.trainticket.platformkit.persistence.OptimisticConcurrencyException}: a version conflict means two writers
 * raced on the same aggregate and the caller should re-read and retry, whereas a duplicate business key means the
 * caller supplied an identifier that someone else already owns and retrying the identical request can never succeed.
 * Both map to HTTP 409, but conflating them (as a bare {@code ON CONFLICT DO NOTHING} does) makes the two
 * indistinguishable in logs and in the error returned to the caller.
 */
public class DuplicateBusinessKeyException extends ApiException {
    private final String keyName;
    private final String keyValue;

    public DuplicateBusinessKeyException(String keyName, String keyValue) {
        this(keyName, keyValue, null);
    }

    public DuplicateBusinessKeyException(String keyName, String keyValue, Throwable cause) {
        super(ApiErrorCode.CONFLICT, keyName + " already exists: " + keyValue, cause);
        this.keyName = keyName;
        this.keyValue = keyValue;
    }

    public String keyName() {
        return keyName;
    }

    public String keyValue() {
        return keyValue;
    }
}
