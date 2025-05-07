FROM 10.10.10.240/library/autoinstrumentation-java:latest as agent
FROM openjdk:8-jre
WORKDIR /app
RUN /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone
COPY --from=agent /javaagent.jar /otel-agent/otel-agent.jar
COPY ./target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-javaagent:/otel-agent/otel-agent.jar", "-Xmx1024m", "-jar", "app.jar"]
