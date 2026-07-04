package com.trainticket.travelerprofile.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PreferenceSnapshot {
    private final Map<String, String> preferences;
    private final Map<String, String> assistanceNeeds;
    private final int version;

    public PreferenceSnapshot(Map<String, String> preferences, Map<String, String> assistanceNeeds, int version) {
        this.preferences = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(preferences, "preferences are required")));
        this.assistanceNeeds = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(assistanceNeeds, "assistanceNeeds are required")));
        if (version < 1) {
            throw new DomainRuleViolation("preference version must be positive");
        }
        this.version = version;
    }

    public Map<String, String> preferences() { return preferences; }
    public Map<String, String> assistanceNeeds() { return assistanceNeeds; }
    public int version() { return version; }

    public PreferenceSnapshot withUpdatedPreference(String key, String value) {
        requireText(key, "preferenceKey");
        requireText(value, "preferenceValue");
        Map<String, String> updated = new HashMap<>(preferences);
        updated.put(key, value);
        return new PreferenceSnapshot(updated, assistanceNeeds, version + 1);
    }

    public PreferenceSnapshot withRemovedPreference(String key) {
        requireText(key, "preferenceKey");
        Map<String, String> updated = new HashMap<>(preferences);
        updated.remove(key);
        return new PreferenceSnapshot(updated, assistanceNeeds, version + 1);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
