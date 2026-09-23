package com.trainticket.postsales;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.ChangeExecutionGuard;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;

@EnabledIfEnvironmentVariable(named="TEST_CHANGE_GUARD_CASES",matches=".+")
class ChangeExecutionGuardLiveTest {
    @Test
    void independentCasesUseDedicatedLocksWhileBusinessConnectionsRemainAvailable() throws Exception {
        String schema="change_guard_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=SpringApplication.run(Application.class,"--server.port=0","--post-sales.messaging.redis.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,"--REDIS_URL=redis://localhost:16379")) {
                var source=app.getBean(ObjectMapper.class).readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_GUARD_CASES"))));
                assertEquals(ChangeExecutionGuard.MAX_CONCURRENT_EXECUTIONS,source.size());
                var guard=app.getBean(ChangeExecutionGuard.class);
                var locks=(HikariDataSource)app.getBean("changeExecutionLockDataSource",DataSource.class);
                var business=(HikariDataSource)app.getBean(DataSource.class);
                assertNotSame(locks,business);
                var acquired=new CountDownLatch(source.size());var finish=new CountDownLatch(1);
                try(var workers=Executors.newFixedThreadPool(source.size())) {
                    var calls=new ArrayList<Future<Boolean>>();
                    for(var row:source) {
                        String id=row.asText();assertFalse(id.isBlank());
                        calls.add(workers.submit(()->guard.execute(id,()->{
                            acquired.countDown();
                            try { if(!finish.await(10,TimeUnit.SECONDS))throw new IllegalStateException("guard test timed out"); }
                            catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                            return true;
                        },()->false)));
                    }
                    try {
                        boolean all=acquired.await(5,TimeUnit.SECONDS);
                        if(!all)for(var call:calls)if(call.isDone())assertTrue(call.get(),"independent case unexpectedly busy");
                        assertTrue(all,"missing="+acquired.getCount()+" lockActive="+locks.getHikariPoolMXBean().getActiveConnections()+" businessActive="+business.getHikariPoolMXBean().getActiveConnections());
                        assertEquals(source.size(),locks.getHikariPoolMXBean().getActiveConnections());
                        try(var connection=business.getConnection();var result=connection.createStatement().executeQuery("SELECT 1")) {
                            assertTrue(result.next());assertEquals(1,result.getInt(1));
                        }
                        String key="post-sales-change-execution:"+source.get(0).asText();
                        try(var connection=business.getConnection();var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))")) {
                            statement.setString(1,key);
                            try(var result=statement.executeQuery()){assertTrue(result.next());assertFalse(result.getBoolean(1),"another session cannot execute the same case");}
                        }
                    } finally { finish.countDown(); }
                    for(var call:calls)assertTrue(call.get(5,TimeUnit.SECONDS));
                }
                assertEquals(0,locks.getHikariPoolMXBean().getActiveConnections());
                String id=source.get(0).asText();
                assertThrows(IllegalStateException.class,()->guard.execute(id,()->{throw new IllegalStateException("guarded command failure");},()->false));
                assertTrue(guard.execute("psc-"+id,()->true,()->false));
                assertEquals(0,locks.getHikariPoolMXBean().getActiveConnections());
            } finally {admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
