package com.trainticket.financesettlement.adapters.http;

import java.util.Map;

public record ApiError(String code, String message, String correlationId, Map<String, Object> details) {}
