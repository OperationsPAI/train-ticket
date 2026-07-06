# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /src
COPY platform/java-kit/pom.xml ./platform/java-kit/pom.xml
COPY platform/java-kit/src ./platform/java-kit/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f platform/java-kit/pom.xml -DskipTests install
COPY services/finance-settlement/pom.xml ./services/finance-settlement/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -f services/finance-settlement/pom.xml -DskipTests dependency:go-offline
COPY services/finance-settlement/src ./services/finance-settlement/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f services/finance-settlement/pom.xml -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --home-dir /home/app app
WORKDIR /app
COPY --from=builder /src/services/finance-settlement/target/*.jar /app/service.jar
USER app
ENV PORT=8080 SERVER_PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
