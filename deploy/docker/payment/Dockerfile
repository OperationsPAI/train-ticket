# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /src
COPY platform/java-kit/pom.xml ./platform/java-kit/pom.xml
COPY platform/java-kit/src ./platform/java-kit/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f platform/java-kit/pom.xml -DskipTests install
COPY services/payment/pom.xml ./services/payment/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -f services/payment/pom.xml -DskipTests dependency:go-offline
COPY services/payment/src ./services/payment/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f services/payment/pom.xml -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --home-dir /home/app app
WORKDIR /app
COPY --from=builder /src/services/payment/target/*.jar /app/service.jar
# MigrationRunner reads ./migrations relative to WORKDIR /app at startup.
COPY services/payment/migrations /app/migrations
USER app
ENV PORT=8080 SERVER_PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
