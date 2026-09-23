package com.trainticket.journeyorder.config;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import io.lettuce.core.RedisClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class IndependentIdentitySubscriberLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_PAYMENT_FILTER_EVENTS",matches=".+")
    void blockedIdentityProjectionLeavesPaymentConsumerAvailable() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var rows=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAYMENT_FILTER_EVENTS"))));
        var redis=RedisClient.create(System.getenv("TEST_REDIS_URL"));
        String group="order-isolation-"+UUID.randomUUID(),identity="events:identity-verification",payment="events:payment";
        var entered=new CountDownLatch(5);var release=new CountDownLatch(1);var accepted=new CountDownLatch(1);
        var config=new AppConfig();
        try(var connection=redis.connect();var main=RedisEventSubscriber.fromUrl(System.getenv("TEST_REDIS_URL"),mapper,1)){
            var operations=new com.trainticket.platformkit.messaging.LettuceRedisStreamOperations(connection);
            operations.createGroup(identity,group);operations.createGroup(payment,group);
            for(int index=0;index<40;index++)operations.publish(identity,mapper.writeValueAsString(rows.get(index)));
            var subscriber=config.journeyOrderEventSubscriber(main,mapper);
            subscriber.subscribe(List.of(identity,payment),group,"consumer",event->{
                if(event.eventType().equals("SegmentReservationRequested")){accepted.countDown();return new EventSubscriber.Success();}
                entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("identity release timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return new EventSubscriber.Success();
            });
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var required=java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(row->row.path("eventType").asText().equals("SegmentReservationRequested")).findFirst().orElseThrow();
                operations.publish(payment,mapper.writeValueAsString(required));
                assertTrue(accepted.await(2,TimeUnit.SECONDS));
            }finally{release.countDown();subscriber.shutdown();connection.sync().xgroupDestroy(identity,group);connection.sync().xgroupDestroy(payment,group);}
        }finally{redis.shutdown();}
    }
}
