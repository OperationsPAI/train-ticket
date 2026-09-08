package com.trainticket.postsales.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.trainticket.postsales.application.PostSalesRepository;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;

@SpringBootTest(properties = "post-sales.messaging.redis.enabled=false")
class PostSalesControllerTest {
    private static final String OPEN_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073001";
    private static final String EVALUATE_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073002";
    private static final String APPROVE_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073003";
    private static final String VALIDATION_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073004";
    private static final String REPLAY_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073005";
    private static final String REUSED_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073006";
    private static final String GENERATED_CORRELATION_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073007";
    private static final String UUID4_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CONFLICT_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073008";
    private static final String CHANGE_RACE_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073009";

    private MockMvc mockMvc;
    @Autowired
    private PostSalesRepository repository;

    @Autowired
    private com.trainticket.postsales.application.PostSalesPolicyContextStore policyContextStore;

    @Autowired
    void setApplicationContext(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(com.trainticket.postsales.RequestContextFilter.class),
                new com.trainticket.platformkit.idempotency.IdempotencyFilter(
                    context.getBean(com.trainticket.platformkit.idempotency.IdempotencyStore.class),
                    context.getBean(com.trainticket.platformkit.http.CanonicalErrorWriter.class)
                )
            )
            .build();
    }

    @Test
    void openEvaluateApproveAndGetHappyPath() throws Exception {
        String caseId = openCase(OPEN_KEY)
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-test-1"))
            .andExpect(jsonPath("$.caseId", notNullValue()))
            .andExpect(jsonPath("$.journeyOrderId", equalTo("ord-test-1")))
            .andExpect(jsonPath("$.status", equalTo("OPENED")))
            .andReturn().getResponse().getContentAsString().split("\"caseId\":\"")[1].split("\"")[0];

        // Seed the policy context this order's refund needs. Evaluate now REFUSES
        // to price a refund without one (409 POLICY_CONTEXT_NOT_READY) instead of
        // falling back to departureTime=now, which forces
        // AFTER_DEPARTURE_NON_REFUNDABLE and a zero refund.
        //
        // This test previously asserted `refundableAmount.minorUnits == 0` as the
        // happy path, which is exactly that wrong answer -- it had encoded the bug
        // as the expectation. A 30-day-out departure lands in TIER_GT_15_DAYS,
        // a 5% penalty, so 100.00 refunds 95.00.
        policyContextStore.save(new com.trainticket.postsales.application.PostSalesPolicyContext(
            "ord-test-1",
            java.time.Instant.now().plus(java.time.Duration.ofDays(30)),
            java.util.Map.of(),
            1,
            0,
            com.trainticket.postsales.domain.Money.of("100.00", "CNY"),
            com.trainticket.postsales.application.PostSalesPolicyContext.RefundWaterfallComponents.empty(
                java.util.Currency.getInstance("CNY"))
        ));

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", caseId)
                .header("Idempotency-Key", EVALUATE_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.eligible", equalTo(true)))
            .andExpect(jsonPath("$.refundableAmount.currency", equalTo("CNY")))
            .andExpect(jsonPath("$.refundableAmount.minorUnits", equalTo(9500)));

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", caseId)
                .header("Idempotency-Key", APPROVE_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.status", equalTo("APPROVED")));

        mockMvc.perform(get("/api/v1/post-sales-cases/{caseId}", caseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.status", equalTo("APPROVED")));
    }

    @Test
    void validationFailureUsesCanonicalErrorBody() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", VALIDATION_KEY)
                .header("X-Correlation-Id", "corr-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.correlationId", equalTo("corr-validation")))
            .andExpect(jsonPath("$.details", notNullValue()));
    }

    @Test
    void idempotentReplayReturnsOriginalOpenResult() throws Exception {
        MvcResult first = openCase(REPLAY_KEY, "ord-replay-1").andExpect(status().isCreated()).andReturn();
        openCase(REPLAY_KEY, "ord-replay-1")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.caseId", equalTo(first.getResponse().getContentAsString().split("\"caseId\":\"")[1].split("\"")[0])));
    }

    @Test
    void idempotencyKeyReuseWithDifferentBodyReturns422() throws Exception {
        openCase(REUSED_KEY, "ord-reused-1").andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", REUSED_KEY)
                .header("X-Correlation-Id", "corr-test-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "journeyOrderId": "ord-test-different",
                      "caseType": "REFUND",
                      "scope": {
                        "orderItemRefs": ["oi-test-1"],
                        "segmentRefs": ["seg-test-1"],
                        "travelerRefs": ["tvl-test-1"],
                        "entitlementRefs": ["ent-test-1"]
                      },
                      "reasonCode": "CUSTOMER_REQUEST",
                      "actorRef": "acct-test-1"
                    }
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("IDEMPOTENCY_KEY_REUSED")))
            .andExpect(jsonPath("$.correlationId", equalTo("corr-test-1")));
    }


    @Test
    void differentIdempotencyKeyForSameRefundOrderReturns409() throws Exception {
        openCase(CONFLICT_KEY, "ord-conflict-1").andExpect(status().isCreated());

        openCase("01890f47-9b7c-7cc2-98c4-dc0c0c073010", "ord-conflict-1")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("REFUND_ALREADY_IN_PROGRESS")))
            .andExpect(jsonPath("$.details.existingCaseId", notNullValue()));
    }

    @Test
    void refundAndChangeRaceUsesSameOrderConflictSlot() throws Exception {
        openCase(CHANGE_RACE_KEY, "ord-change-race-1").andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", "01890f47-9b7c-7cc2-98c4-dc0c0c073011")
                .header("X-Correlation-Id", "corr-test-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(openCaseJson("ord-change-race-1", "CHANGE")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("REFUND_ALREADY_IN_PROGRESS")));
    }

    @Test
    void concurrentRefundsForSameOrderAllowExactlyOneSuccess() throws Exception {
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int index = i;
            calls.add(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/post-sales-cases")
                        .header("Idempotency-Key", "01890f47-9b7c-7cc2-98c4-dc0c0c0731%02d".formatted(index))
                        .header("X-Correlation-Id", "corr-concurrent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openCaseJson("ord-concurrent-refund-1", "REFUND")))
                    .andReturn().getResponse().getStatus();
            });
        }

        var futures = calls.stream().map(executor::submit).toList();
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var future : futures) {
            statuses.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        org.junit.jupiter.api.Assertions.assertEquals(1, statuses.stream().filter(status -> status == 201).count());
        org.junit.jupiter.api.Assertions.assertEquals(workers - 1, statuses.stream().filter(status -> status == 409).count());
    }


    @Test
    void evaluatingNonRefundableCaseReturns422MachineCode() throws Exception {
        PostSalesCase postSalesCase = PostSalesCase.open(
            "ord-non-refundable-1",
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket("oi-non-refundable-1", "seg-non-refundable-1", "tvl-non-refundable-1", "ent-non-refundable-1"),
            "CUSTOMER_REQUEST",
            "acct-test-1",
            "01890f47-9b7c-7cc2-98c4-dc0c0c073012",
            Instant.parse("2026-07-05T10:30:00Z"),
            "cmd-non-refundable",
            "corr-test-1"
        );
        postSalesCase.reject("RULE_BLOCKED", Instant.parse("2026-07-05T10:31:00Z"), "cmd-reject", "cmd-non-refundable", "corr-test-1");
        repository.save(postSalesCase);

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", "psc-" + postSalesCase.caseId())
                .header("Idempotency-Key", "01890f47-9b7c-7cc2-98c4-dc0c0c073013")
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("ORDER_NOT_REFUNDABLE")));
    }

    @Test
    void missingCorrelationHeaderUsesGeneratedRequestCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", GENERATED_CORRELATION_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "journeyOrderId": "ord-generated-correlation",
                      "caseType": "REFUND",
                      "scope": {
                        "orderItemRefs": ["oi-test-1"],
                        "segmentRefs": ["seg-test-1"],
                        "travelerRefs": ["tvl-test-1"],
                        "entitlementRefs": ["ent-test-1"]
                      },
                      "reasonCode": "CUSTOMER_REQUEST",
                      "actorRef": "acct-test-1"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().exists("X-Correlation-Id"))
            .andExpect(header().string("X-Correlation-Id", not(equalTo(GENERATED_CORRELATION_KEY))));
    }

    @Test
    void openRejectsMalformedIdempotencyKey() throws Exception {
        openCase("not-a-uuid")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void openRejectsUuid4IdempotencyKey() throws Exception {
        openCase(UUID4_KEY)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void evaluateRejectsMalformedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", "psc-test-case")
                .header("Idempotency-Key", "not-a-uuid")
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void evaluateRejectsUuid4IdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", "psc-test-case")
                .header("Idempotency-Key", UUID4_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void approveRejectsMalformedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", "psc-test-case")
                .header("Idempotency-Key", "not-a-uuid")
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void approveRejectsUuid4IdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", "psc-test-case")
                .header("Idempotency-Key", UUID4_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    private org.springframework.test.web.servlet.ResultActions openCase(String idempotencyKey) throws Exception {
        return openCase(idempotencyKey, "ord-test-1");
    }

    private org.springframework.test.web.servlet.ResultActions openCase(String idempotencyKey, String orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/post-sales-cases")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Correlation-Id", "corr-test-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(openCaseJson(orderId, "REFUND")));
    }

    private static String openCaseJson(String orderId, String caseType) {
        return """
                {
                  "journeyOrderId": "%s",
                  "caseType": "%s",
                  "scope": {
                    "orderItemRefs": ["oi-test-1"],
                    "segmentRefs": ["seg-test-1"],
                    "travelerRefs": ["tvl-test-1"],
                    "entitlementRefs": ["ent-test-1"]
                  },
                  "reasonCode": "CUSTOMER_REQUEST",
                  "actorRef": "acct-test-1"
                }
                """.formatted(orderId, caseType);
    }
}
