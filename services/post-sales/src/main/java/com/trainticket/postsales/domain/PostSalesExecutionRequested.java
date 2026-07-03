package com.trainticket.postsales.domain;

import java.util.List;

public record PostSalesExecutionRequested(
    String caseId,
    List<PostSalesStepType> orderedSteps,
    EventMetadata metadata
) implements PostSalesEvent { }
