package com.trainticket.platformkit.messaging;

public sealed interface HandlerResult permits HandlerResult.Success, HandlerResult.TransientError, HandlerResult.FatalError {
    record Success() implements HandlerResult {}
    record TransientError(String reason) implements HandlerResult {}
    record FatalError(String reason) implements HandlerResult {}
}
