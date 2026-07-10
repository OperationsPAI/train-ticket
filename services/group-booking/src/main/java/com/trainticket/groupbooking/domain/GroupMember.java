package com.trainticket.groupbooking.domain;

import java.time.Instant;
import java.util.Objects;

public final class GroupMember {
    private final String memberId;
    private final String travelerRef;
    private final String maskedDocumentRef;
    private final Instant addedAt;
    private GroupMemberStatus status;

    private GroupMember(String memberId, String travelerRef, String maskedDocumentRef, GroupMemberStatus status, Instant addedAt) {
        this.memberId = requireText(memberId, "memberId");
        this.travelerRef = requireText(travelerRef, "travelerRef");
        this.maskedDocumentRef = requireMasked(maskedDocumentRef);
        this.status = Objects.requireNonNull(status, "status is required");
        this.addedAt = Objects.requireNonNull(addedAt, "addedAt is required");
    }

    public static GroupMember active(String memberId, String travelerRef, String maskedDocumentRef, Instant now) {
        return new GroupMember(memberId, travelerRef, maskedDocumentRef, GroupMemberStatus.ACTIVE, now);
    }

    public static GroupMember rehydrate(String memberId, String travelerRef, String maskedDocumentRef, GroupMemberStatus status, Instant addedAt) {
        return new GroupMember(memberId, travelerRef, maskedDocumentRef, status, addedAt);
    }

    public void cancel() {
        if (status == GroupMemberStatus.CANCELLED) {
            return;
        }
        status = GroupMemberStatus.CANCELLED;
    }

    public boolean active() {
        return status == GroupMemberStatus.ACTIVE;
    }

    public String memberId() { return memberId; }
    public String travelerRef() { return travelerRef; }
    public String maskedDocumentRef() { return maskedDocumentRef; }
    public GroupMemberStatus status() { return status; }
    public Instant addedAt() { return addedAt; }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolation(name + " is required");
        }
        return value.trim();
    }

    private static String requireMasked(String value) {
        String normalized = requireText(value, "maskedDocumentRef");
        if (!normalized.contains("*") && !normalized.startsWith("docref-")) {
            throw new DomainRuleViolation("maskedDocumentRef must be masked or a document reference");
        }
        return normalized;
    }
}
