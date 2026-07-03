package com.trainticket.postsales.domain;

public sealed interface PostSalesEvent permits PostSalesRequested, PostSalesEvaluated, PostSalesApproved, PostSalesRejected, PostSalesExecutionRequested, PostSalesStepSucceeded, PostSalesStepFailed, PostSalesManualReviewRequired, PostSalesApplied {
    String caseId();
    EventMetadata metadata();
}
