package com.trainticket.bookingorchestration.application;

public sealed interface HandlerResult {
    record Success() implements HandlerResult {}
    record TransientError(String reason) implements HandlerResult {}
    record FatalError(String reason) implements HandlerResult {}
}
