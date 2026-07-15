package com.trainticket.travelerprofile.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.travelerprofile.application.ApiDocumentType;
import com.trainticket.travelerprofile.application.TravelerType;
import com.trainticket.travelerprofile.application.TravelerState;

import com.trainticket.travelerprofile.domain.Document;
import com.trainticket.travelerprofile.domain.DocumentStatus;
import com.trainticket.travelerprofile.domain.DocumentType;
import com.trainticket.travelerprofile.domain.EligibilitySummary;
import com.trainticket.travelerprofile.domain.ExternalVerificationFact;
import com.trainticket.travelerprofile.domain.ExternalVerificationStatus;
import com.trainticket.travelerprofile.domain.PreferenceSnapshot;
import com.trainticket.travelerprofile.domain.TravelerProfile;
import com.trainticket.travelerprofile.domain.TravelerProfileEvent;
import com.trainticket.travelerprofile.domain.TravelerProfileStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TravelerJson {
    private TravelerJson() {}

    public static TravelerSnapshot snapshot(TravelerState state, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        TravelerProfile profile = state.aggregate();
        root.put("profileId", profile.profileId());
        root.put("travelerId", state.travelerId());
        root.put("travelerRef", profile.travelerRef());
        root.put("accountId", state.accountId());
        root.put("aggregateAccountId", profile.accountId());
        root.put("displayName", profile.displayName());
        root.put("birthDate", profile.birthDate());
        root.put("status", profile.status().name());
        putNullable(root, "statusReason", profile.statusReason());
        root.put("snapshotVersion", state.snapshotVersion());
        root.put("travelerType", state.travelerType().name());
        root.put("givenName", state.givenName());
        root.put("familyName", state.familyName());
        putNullable(root, "contactEmail", state.contactEmail());
        putNullable(root, "contactPhone", state.contactPhone());
        root.put("createdAt", state.createdAt().toString());
        root.put("updatedAt", state.updatedAt().toString());
        ObjectNode preferences = root.putObject("preferences");
        preferences.set("values", objectMapper.valueToTree(profile.preferences().preferences()));
        preferences.set("assistanceNeeds", objectMapper.valueToTree(profile.preferences().assistanceNeeds()));
        preferences.put("version", profile.preferences().version());
        ArrayNode documents = root.putArray("documents");
        for (Document document : profile.documents()) {
            ObjectNode node = documents.addObject();
            node.put("documentId", document.documentId());
            node.put("documentType", document.documentType().name());
            node.put("maskedDocumentRef", document.maskedDocumentRef());
            node.put("documentNumberHash", document.documentNumberHash());
            node.put("issuingCountry", document.issuingCountry());
            node.put("issuedAt", document.issuedAt().toString());
            node.put("expiresAt", document.expiresAt().toString());
            node.put("displayName", document.displayName());
            node.put("primaryDocument", profile.primaryDocument() != null && profile.primaryDocument().documentId().equals(document.documentId()));
            node.put("status", document.status().name());
            putNullable(node, "statusReason", document.statusReason());
            putNullable(node, "verifiedAt", document.verifiedAt() == null ? null : document.verifiedAt().toString());
            putNullable(node, "verifier", document.verifier());
        }
        ArrayNode eligibilities = root.putArray("eligibilitySummaries");
        for (EligibilitySummary summary : profile.eligibilitySummaries()) {
            ObjectNode node = eligibilities.addObject();
            node.put("eligibilityId", summary.eligibilityId());
            node.put("eligibilityType", summary.eligibilityType());
            node.put("eligibilitySource", summary.eligibilitySource());
            node.put("evidenceHash", summary.evidenceHash());
            node.put("validFrom", summary.validFrom().toString());
            node.put("validUntil", summary.validUntil().toString());
            node.put("revoked", summary.revoked());
            putNullable(node, "revocationReason", summary.revocationReason());
        }
        ArrayNode verificationFacts = root.putArray("verificationFacts");
        for (ExternalVerificationFact fact : profile.verificationFacts()) {
            ObjectNode node = verificationFacts.addObject();
            node.put("credentialRecordId", fact.credentialRecordId());
            putNullable(node, "verificationCaseId", fact.verificationCaseId());
            node.put("status", fact.status().name());
            putNullable(node, "documentType", fact.documentType());
            putNullable(node, "maskedDocumentNo", fact.maskedDocumentNo());
            putNullable(node, "documentHash", fact.documentHash());
            putNullable(node, "policyVersion", fact.policyVersion());
            putNullable(node, "reasonCode", fact.reasonCode());
            putNullable(node, "validFrom", fact.validFrom() == null ? null : fact.validFrom().toString());
            putNullable(node, "validUntil", fact.validUntil() == null ? null : fact.validUntil().toString());
            node.put("recordedAt", fact.recordedAt().toString());
            node.put("sourceEventId", fact.sourceEventId());
        }
        root.set("domainEvents", objectMapper.valueToTree(profile.domainEvents()));
        return new TravelerSnapshot(root);
    }

    public static TravelerState toState(TravelerSnapshot snapshot, ObjectMapper objectMapper) {
        ObjectNode root = snapshot.data();
        List<Document> documents = new ArrayList<>();
        String primaryDocumentId = null;
        for (JsonNode node : root.path("documents")) {
            Document document = Document.rehydrate(
                text(node, "documentId"), DocumentType.valueOf(text(node, "documentType")), maskedDocumentRef(node), documentNumberHash(node), text(node, "issuingCountry"),
                Instant.parse(text(node, "issuedAt")), Instant.parse(text(node, "expiresAt")), text(node, "displayName"), node.path("primaryDocument").asBoolean(false),
                DocumentStatus.valueOf(text(node, "status")), node.path("statusReason").asText(null), nullableInstant(node, "verifiedAt"), node.path("verifier").asText(null));
            documents.add(document);
            if (node.path("primaryDocument").asBoolean(false)) primaryDocumentId = document.documentId();
        }
        List<EligibilitySummary> eligibilities = new ArrayList<>();
        for (JsonNode node : root.path("eligibilitySummaries")) {
            eligibilities.add(EligibilitySummary.rehydrate(text(node, "eligibilityId"), text(node, "eligibilityType"), text(node, "eligibilitySource"), text(node, "evidenceHash"), Instant.parse(text(node, "validFrom")), Instant.parse(text(node, "validUntil")), node.path("revoked").asBoolean(false), node.path("revocationReason").asText(null)));
        }
        List<ExternalVerificationFact> verificationFacts = new ArrayList<>();
        for (JsonNode node : root.path("verificationFacts")) {
            verificationFacts.add(new ExternalVerificationFact(
                text(node, "credentialRecordId"), nullableText(node, "verificationCaseId"), ExternalVerificationStatus.valueOf(text(node, "status")),
                nullableText(node, "documentType"), nullableText(node, "maskedDocumentNo"), nullableText(node, "documentHash"),
                nullableText(node, "policyVersion"), nullableText(node, "reasonCode"), nullableInstant(node, "validFrom"),
                nullableInstant(node, "validUntil"), Instant.parse(text(node, "recordedAt")), text(node, "sourceEventId")
            ));
        }
        JsonNode pref = root.path("preferences");
        Map<String, String> values = map(pref.path("values"), objectMapper);
        Map<String, String> assistance = map(pref.path("assistanceNeeds"), objectMapper);
        TravelerProfile profile = TravelerProfile.rehydrate(
            text(root, "profileId"), text(root, "travelerRef"), text(root, "aggregateAccountId"), text(root, "displayName"), text(root, "birthDate"),
            TravelerProfileStatus.valueOf(text(root, "status")), root.path("statusReason").asText(null), documents, primaryDocumentId, eligibilities,
            verificationFacts, new PreferenceSnapshot(values, assistance, pref.path("version").asInt(1)), List.of());
        return new TravelerState(profile, text(root, "travelerId"), text(root, "snapshotVersion"), text(root, "accountId"), TravelerType.valueOf(text(root, "travelerType")), text(root, "givenName"), text(root, "familyName"), root.path("contactEmail").asText(null), root.path("contactPhone").asText(null), Instant.parse(text(root, "createdAt")), Instant.parse(text(root, "updatedAt")));
    }

    private static Map<String, String> map(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        return objectMapper.convertValue(node, objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
    }

    private static Instant nullableInstant(JsonNode node, String field) { String value = node.path(field).asText(null); return value == null || value.isBlank() ? null : Instant.parse(value); }
    private static String nullableText(JsonNode node, String field) { String value = node.path(field).asText(null); return value == null || value.isBlank() ? null : value; }
    private static String maskedDocumentRef(JsonNode node) {
        String value = node.path("maskedDocumentRef").asText(null);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String legacyDocumentNumber = text(node, "documentNumber");
        return Document.mask(legacyDocumentNumber);
    }
    private static String documentNumberHash(JsonNode node) {
        String value = node.path("documentNumberHash").asText(null);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return Document.hash(text(node, "documentNumber"));
    }
    private static String text(JsonNode node, String field) { String value = node.path(field).asText(null); if (value == null || value.isBlank()) throw new IllegalStateException(field + " is missing from traveler snapshot"); return value; }
    private static void putNullable(ObjectNode node, String field, String value) { if (value == null) node.putNull(field); else node.put(field, value); }

    public record TravelerSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) public static TravelerSnapshot of(ObjectNode data) { return new TravelerSnapshot(data); }
    }
}
