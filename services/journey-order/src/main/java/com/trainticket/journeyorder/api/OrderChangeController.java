package com.trainticket.journeyorder.api;
import com.trainticket.journeyorder.application.service.OrderChangeService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/journey-orders")
public class OrderChangeController {
    private final OrderChangeService service;
    public OrderChangeController(OrderChangeService service){this.service=service;}
    @PostMapping("/{orderId}/apply-change")
    public Map<String,Object> apply(@PathVariable String orderId,@RequestBody Request request,@RequestHeader("X-Correlation-Id")String correlation){return service.apply(orderId,request.caseId(),correlation);}
    public record Request(String caseId){}
}
