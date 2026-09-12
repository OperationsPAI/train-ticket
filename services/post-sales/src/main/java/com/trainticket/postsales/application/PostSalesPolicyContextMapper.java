package com.trainticket.postsales.application;

import com.trainticket.postsales.application.PostSalesPolicyContext.RefundWaterfallComponents;
import com.trainticket.postsales.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PostSalesPolicyContextMapper {
    private static final String DEFAULT_CURRENCY = "CNY";

    private PostSalesPolicyContextMapper() {
    }

    static Optional<PostSalesPolicyContext> fromEventPayload(Map<?, ?> payload) {
        String journeyOrderId = text(payload, "orderId")
            .or(() -> text(payload, "journeyOrderId"))
            .orElse(null);
        if (journeyOrderId == null) {
            return Optional.empty();
        }
        String currency = currency(payload);
        List<Map<?, ?>> segmentPayloads = mapList(payload.get("segments"));
        Optional<Instant> departureTime = earliestDeparture(payload, segmentPayloads);
        if (departureTime.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> travelerTypes = travelerTypes(payload.get("travelerRefs"));
        int groupSize = travelerTypes.isEmpty() ? Math.max(1, textList(payload.get("travelerRefs")).size()) : travelerTypes.size();
        RefundWaterfallComponents components = refundComponents(payload, currency);
        Money originalFare = components.baseFare().isZero() ? totalMoney(payload, currency) : components.baseFare();
        int appliedChangeCount = integer(payload, "appliedChangeCount")
            .or(() -> integer(payload, "changeCount"))
            .or(() -> countAppliedChangeEvents(payload.get("postSalesAdjustments")))
            .or(() -> countAppliedChangeEvents(payload.get("adjustments")))
            .orElse(0);

        return Optional.of(new PostSalesPolicyContext(
            journeyOrderId,
            departureTime.get(),
            travelerTypes,
            groupSize,
            appliedChangeCount,
            originalFare,
            components
        ));
    }

    private static final Pattern SEGMENT_REF_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private static Optional<Instant> earliestDeparture(Map<?, ?> payload, List<Map<?, ?>> segments) {
        List<Instant> candidates = new ArrayList<>();
        for (String field : List.of("departureTime", "departureAt")) {
            text(payload, field).map(Instant::parse).ifPresent(candidates::add);
        }
        for (Map<?, ?> segment : segments) {
            for (String field : List.of("departureTime", "departureAt")) {
                text(segment, field).map(Instant::parse).ifPresent(candidates::add);
            }
        }
        // A JourneyOrderCreated/Confirmed payload may carry only segmentRefs — canonical
        // slugs such as seg-web-2026-08-01-<hash> — with no explicit departure time. The
        // policy context would then be dropped entirely and every refund quote returns
        // zero. The service date is embedded in the slug, so derive start-of-day UTC from
        // it as a last-resort departure.
        if (candidates.isEmpty()) {
            for (String segmentRef : textList(payload.get("segmentRefs"))) {
                departureFromSegmentRef(segmentRef).ifPresent(candidates::add);
            }
        }
        return PostSalesPolicyContext.earliest(candidates);
    }

    private static Optional<Instant> departureFromSegmentRef(String segmentRef) {
        if (segmentRef == null) {
            return Optional.empty();
        }
        Matcher matcher = SEGMENT_REF_DATE.matcher(segmentRef);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
            ).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, String> travelerTypes(Object travelerRefsValue) {
        Map<String, String> travelerTypes = new LinkedHashMap<>();
        if (travelerRefsValue instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> traveler) {
                    String travelerId = text(traveler, "travelerId")
                        .or(() -> text(traveler, "travelerRef"))
                        .orElse(null);
                    String travelerType = text(traveler, "travelerType")
                        .or(() -> text(traveler, "documentType"))
                        .orElse("UNKNOWN");
                    if (travelerId != null) {
                        travelerTypes.put(travelerId, travelerType);
                    }
                } else if (value instanceof String travelerId && !travelerId.isBlank()) {
                    travelerTypes.put(travelerId, "UNKNOWN");
                }
            }
        }
        return travelerTypes;
    }

    /**
     * The fare and waterfall components from a payload that carries a monetary
     * summary but not the segments needed for a full context.
     *
     * JourneyOrderConfirmed and JourneyOrderPostSalesAdjusted are exactly that:
     * they update what is refundable without restating the itinerary. Returns
     * empty when the payload has no usable amount.
     */
    static Optional<FareUpdate> fareUpdateFromEventPayload(Map<?, ?> payload) {
        String currency = currency(payload);
        RefundWaterfallComponents components = refundComponents(payload, currency);
        Money fare = components.baseFare().isZero() ? totalMoney(payload, currency) : components.baseFare();
        if (fare.isZero()) {
            return Optional.empty();
        }
        return Optional.of(new FareUpdate(fare, components));
    }

    /** A fare and its component breakdown, without any itinerary detail. */
    record FareUpdate(Money originalFare, RefundWaterfallComponents components) {
    }

    private static RefundWaterfallComponents refundComponents(Map<?, ?> payload, String currency) {
        List<Map<?, ?>> items = mapList(payload.get("orderItems"));
        if (items.isEmpty()) {
            items = mapList(payload.get("lineItems"));
        }
        Money zero = Money.zero(currency);
        Money baseFare = zero;
        Money taxes = zero;
        Money platformServiceFee = zero;
        Money supplierServiceFee = zero;
        Money ancillary = zero;
        Money discounts = zero;
        for (Map<?, ?> item : items) {
            Money amount = amount(item, currency).orElse(zero);
            String type = text(item, "type")
                .or(() -> text(item, "itemType"))
                .or(() -> text(item, "kind"))
                .orElse("")
                .toUpperCase(Locale.ROOT);
            if (type.contains("TAX")) {
                taxes = taxes.add(amount);
            } else if (type.contains("PLATFORM") && type.contains("FEE")) {
                platformServiceFee = platformServiceFee.add(amount);
            } else if (type.contains("SUPPLIER") && type.contains("FEE")) {
                supplierServiceFee = supplierServiceFee.add(amount);
            } else if (type.contains("SERVICE") && type.contains("FEE")) {
                platformServiceFee = platformServiceFee.add(amount);
            } else if (type.contains("ANCILLARY") || type.contains("INSURANCE") || type.contains("MEAL") || type.contains("UPGRADE")) {
                ancillary = ancillary.add(amount);
            } else if (type.contains("DISCOUNT")) {
                discounts = discounts.add(amount);
            } else {
                baseFare = baseFare.add(amount);
            }
        }
        if (items.isEmpty()) {
            baseFare = moneyAt(payload, List.of("monetarySummary", "subtotal"), currency)
                .or(() -> moneyAt(payload, List.of("monetarySummary", "total"), currency))
                .orElse(zero);
        }
        boolean ancillaryUsed = booleanValue(payload.get("ancillaryUsed"));
        return new RefundWaterfallComponents(baseFare, taxes, platformServiceFee, supplierServiceFee, ancillary, discounts, ancillaryUsed);
    }

    private static Money totalMoney(Map<?, ?> payload, String currency) {
        return moneyAt(payload, List.of("monetarySummary", "total"), currency)
            .or(() -> moneyAt(payload, List.of("monetarySummary", "subtotal"), currency))
            .orElse(Money.zero(currency));
    }

    private static String currency(Map<?, ?> payload) {
        Object monetarySummary = nested(payload, List.of("monetarySummary")).orElse(Map.of());
        return monetarySummary instanceof Map<?, ?> map ? text(map, "currency").orElse(DEFAULT_CURRENCY) : DEFAULT_CURRENCY;
    }

    private static Optional<Money> amount(Map<?, ?> item, String currency) {
        return moneyAt(item, List.of("amount"), currency)
            .or(() -> moneyAt(item, List.of("price"), currency))
            .or(() -> moneyAt(item, List.of("total"), currency));
    }

    private static Optional<Money> moneyAt(Map<?, ?> payload, List<String> path, String fallbackCurrency) {
        Optional<Object> value = nested(payload, path);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Object raw = value.get();
        if (raw instanceof Map<?, ?> map) {
            String currency = text(map, "currency").orElse(fallbackCurrency);
            Optional<Integer> minorUnits = integer(map, "minorUnits");
            if (minorUnits.isPresent()) {
                return Optional.of(Money.fromMinorUnits(minorUnits.get().longValue(), currency));
            }
            Optional<String> amount = text(map, "amount");
            if (amount.isPresent()) {
                return Optional.of(Money.of(amount.get(), currency));
            }
        }
        if (raw instanceof Number number) {
            return Optional.of(Money.fromMinorUnits(number.longValue(), fallbackCurrency));
        }
        return Optional.empty();
    }

    private static Optional<Object> nested(Map<?, ?> payload, List<String> path) {
        Object current = payload;
        for (String field : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(field)) {
                return Optional.empty();
            }
            current = map.get(field);
        }
        return Optional.ofNullable(current);
    }

    private static Optional<String> text(Map<?, ?> payload, String field) {
        Object value = payload.get(field);
        return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    private static Optional<Integer> integer(Map<?, ?> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return Optional.of(Integer.parseInt(text));
        }
        return Optional.empty();
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static Optional<Integer> countAppliedChangeEvents(Object value) {
        if (!(value instanceof List<?> values)) {
            return Optional.empty();
        }
        int count = 0;
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                String type = text(map, "caseType").or(() -> text(map, "type")).orElse("");
                String status = text(map, "status").orElse("APPLIED");
                if (type.equalsIgnoreCase("CHANGE") && status.equalsIgnoreCase("APPLIED")) {
                    count++;
                }
            }
        }
        return Optional.of(count);
    }

    private static List<Map<?, ?>> mapList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                maps.add(map);
            }
        }
        return maps;
    }

    private static List<String> textList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(text -> !text.isBlank())
            .toList();
    }
}
