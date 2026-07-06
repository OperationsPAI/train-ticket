package com.trainticket.postsales.domain;

public sealed interface PostSalesEvent permits PostSalesRequested, PostSalesCaseOpened, PostSalesEligibilityEvaluated, PostSalesDecisionQuoted, PostSalesApproved, PostSalesRejected, PostSalesExecutionRequested, PostSalesExecutionStarted, PostSalesStepSucceeded, PostSalesStepFailed, PostSalesManualReviewRequired, PostSalesApplied, PostSalesFailed, ChangeApplied {
    String caseId();
    EventMetadata metadata();
}
