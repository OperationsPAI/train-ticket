package com.trainticket.postsales.api;

import com.trainticket.postsales.application.ChangeExecutionService;
import com.trainticket.postsales.application.PostSalesMapper;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/post-sales-cases")
public class ChangeExecutionController {
    private final ChangeExecutionService execution;
    public ChangeExecutionController(ChangeExecutionService execution){this.execution=execution;}
    @PostMapping("/{caseId}/execute-change")
    public Map<String,Object> execute(@PathVariable String caseId,@RequestHeader("X-Correlation-Id") String correlation){return PostSalesMapper.caseDetails(execution.advance(caseId,correlation));}
    @GetMapping("/{caseId}/issue-authorization/{bookingId}")
    public Map<String,Object> authorize(@PathVariable String caseId,@PathVariable String bookingId,@RequestHeader("X-Correlation-Id") String correlation){return execution.issueAuthorization(caseId,bookingId,correlation);}
}
