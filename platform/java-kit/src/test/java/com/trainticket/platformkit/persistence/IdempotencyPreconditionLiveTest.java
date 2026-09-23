package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyPreconditionLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
    void stateDependentFailureCanBeReplacedOnlyBySameRequest() throws Exception {
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);
        String schema="idem_precondition_"+UUID.randomUUID().toString().replace("-","");
        try(var pool=new HikariDataSource(config)){
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);jdbc.execute("SET search_path TO "+schema);
            try{
                jdbc.execute("CREATE TABLE idempotency_records(key text PRIMARY KEY,request_hash text NOT NULL,status_code int NOT NULL,response_body jsonb,created_at timestamptz default now())");
                var store=new DbIdempotencyStore(pool,new ObjectMapper());
                for(int status:new int[]{409,412}){
                    String key=UUID.randomUUID().toString();
                    var pending=new IdempotencyStore.StoredResponse("same-request",status,"application/json",Map.of(),"{}".getBytes());
                    store.saveIfAbsent(key,pending);
                    var other=new IdempotencyStore.StoredResponse("other-request",201,"application/json",Map.of(),"{}".getBytes());
                    assertEquals(status,store.saveIfAbsent(key,other).status());
                    var completed=new IdempotencyStore.StoredResponse("same-request",201,"application/json",Map.of(),"{}".getBytes());
                    assertEquals(201,store.saveIfAbsent(key,completed).status());
                    assertEquals(201,store.find(key).orElseThrow().status());
                    assertEquals(201,store.saveIfAbsent(key,pending).status());
                }
            }finally{jdbc.execute("SET search_path TO public");jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
