package com.trainticket.travelerprofile.adapters.http;

import java.util.Map;

public record ApiError(
    String code,
    String message,
    String correlationId,
    Map<String, ?> details
) {
}
