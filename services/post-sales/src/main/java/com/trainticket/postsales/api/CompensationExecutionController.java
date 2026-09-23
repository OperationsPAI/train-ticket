package com.trainticket.postsales.api;

import com.trainticket.postsales.application.CompensationExecutionService;
import com.trainticket.postsales.application.PostSalesMapper;
import org.springframework.web.bind.annotation.*;

@RestController
public final class CompensationExecutionController {
    private final CompensationExecutionService service;
    public CompensationExecutionController(CompensationExecutionService service){this.service=service;}
    @PostMapping("/api/v1/post-sales-cases/{caseId}/execute-compensation")
    public java.util.Map<String,Object> execute(@PathVariable String caseId,jakarta.servlet.http.HttpServletRequest request){
        return PostSalesMapper.caseDetails(service.advance(caseId,com.trainticket.platformkit.http.CorrelationIds.from(request)));
    }
}
