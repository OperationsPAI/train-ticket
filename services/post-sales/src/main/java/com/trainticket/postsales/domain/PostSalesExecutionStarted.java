package com.trainticket.postsales.domain;

import java.util.List;

public record PostSalesExecutionStarted(
    String caseId,
    List<PostSalesStepType> orderedSteps,
    String approvalRef,
    EventMetadata metadata
) implements PostSalesEvent { }
