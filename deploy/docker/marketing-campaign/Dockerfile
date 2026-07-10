FROM eclipse-temurin:25-jre
WORKDIR /app
COPY services/marketing-campaign/target/marketing-campaign.jar /app/marketing-campaign.jar
COPY services/marketing-campaign/migrations /app/migrations
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/marketing-campaign.jar"]
