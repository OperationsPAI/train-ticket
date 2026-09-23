package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.*;
import com.trainticket.bookingorchestration.Application;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;

class ChangeRuntimeLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
    void startsWithChangeHttpClientAndPostgres() throws Exception {
        String schema="booking_start_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))){
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=SpringApplication.run(Application.class,"--server.port=0","--booking-orchestration.messaging.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,"--REDIS_URL=redis://localhost:16379")){
                assertNotNull(app.getBean(ChangeReservationController.class));
                assertNotNull(app.getBean(org.springframework.web.client.RestClient.Builder.class));
            }finally{admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
