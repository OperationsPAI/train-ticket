package com.trainticket.postsales.application;

public final class CompensationMismatchException extends RuntimeException {
    private final CompensationMismatchCheckpoint checkpoint;

    public CompensationMismatchException(CompensationMismatchCheckpoint checkpoint) {
        super(checkpoint.reason());
        this.checkpoint = checkpoint;
    }

    public CompensationMismatchCheckpoint checkpoint() { return checkpoint; }
}
