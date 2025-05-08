FROM alpine:3.18

# Install wget to download the file
RUN apk add --no-cache wget

# Download the Java agent JAR file
RUN wget -O /javaagent.jar https://repo1.maven.org/maven2/co/elastic/otel/elastic-otel-javaagent/1.4.1/elastic-otel-javaagent-1.4.1.jar

# Verify download and set permissions
RUN chmod 644 /javaagent.jar