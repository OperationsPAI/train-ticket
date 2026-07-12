FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY platform/java-kit/pom.xml platform/java-kit/pom.xml
COPY platform/java-kit/src platform/java-kit/src
RUN mvn -q -f platform/java-kit/pom.xml -DskipTests install
COPY services/wallet-promotion/pom.xml services/wallet-promotion/pom.xml
COPY services/wallet-promotion/src services/wallet-promotion/src
RUN mvn -q -f services/wallet-promotion/pom.xml -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/services/wallet-promotion/target/wallet-promotion-0.1.0-SNAPSHOT.jar app.jar
COPY services/wallet-promotion/migrations migrations
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
