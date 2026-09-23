package com.trainticket.postsales;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.UuidV7;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_COMPENSATION_SOURCE",matches=".+")
class HttpRequestConcurrencyLiveTest {
    @Test
    void pendingRequestBodiesDoNotBlockCaseReadsOrHealth() throws Exception {
        String schema="post_sales_http_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=SpringApplication.run(Application.class,"--server.port=0","--post-sales.messaging.redis.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,"--REDIS_URL=redis://localhost:16379")) {
                var mapper=app.getBean(ObjectMapper.class);
                var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_COMPENSATION_SOURCE")))).path("post_sales");
                String id=source.path("id").asText();var jdbc=new JdbcTemplate(app.getBean(DataSource.class));
                jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES(?,?,?::jsonb)",id,source.path("version").asLong(),source.path("data").toString());
                var server=(TomcatWebServer)((ServletWebServerApplicationContext)app).getWebServer();
                int port=server.getPort();var virtual=new CompletableFuture<Boolean>();
                server.getTomcat().getConnector().getProtocolHandler().getExecutor().execute(()->virtual.complete(Thread.currentThread().isVirtual()));
                assertTrue(virtual.get(5,TimeUnit.SECONDS));
                var sockets=new ArrayList<Socket>();
                try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()) {
                    for(int index=0;index<220;index++) {
                        var socket=new Socket("127.0.0.1",port);socket.setSoTimeout(5000);sockets.add(socket);
                        String headers="POST /api/v1/post-sales-cases/psc-"+id+"/evaluate HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 2\r\nIdempotency-Key: "+UuidV7.generate()+"\r\nConnection: close\r\n\r\n{";
                        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                    }
                    for(String path:new String[]{"/healthz","/readyz","/api/v1/post-sales-cases/psc-"+id}) {
                        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(200,response.statusCode(),response.body());
                        if(path.contains("post-sales-cases"))assertEquals("psc-"+id,mapper.readTree(response.body()).path("caseId").asText());
                    }
                    for(var socket:sockets){socket.getOutputStream().write('}');socket.getOutputStream().flush();}
                    for(var socket:sockets)assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).startsWith("HTTP/1.1 200"));
                } finally {for(var socket:sockets)socket.close();}
                assertEquals(source.path("data"),mapper.readTree(jdbc.queryForObject("SELECT data::text FROM post_sales_case_snapshots WHERE id=?",String.class,id)));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
            } finally {admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
