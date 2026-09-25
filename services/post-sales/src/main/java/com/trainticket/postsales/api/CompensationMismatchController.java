package com.trainticket.postsales.api;

import com.trainticket.postsales.application.CompensationMismatchCheckpoint;
import com.trainticket.postsales.application.CompensationMismatchStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CompensationMismatchController {
    private final CompensationMismatchStore store;

    public CompensationMismatchController(CompensationMismatchStore store) {
        this.store = store;
    }

    @GetMapping("/api/v1/internal/compensation-mismatches/{caseId}")
    public ResponseEntity<CompensationMismatchCheckpoint> get(@PathVariable String caseId) {
        return store.find(caseId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
