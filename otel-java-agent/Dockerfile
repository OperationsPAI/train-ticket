FROM alpine:3.18

# Install wget to download the file
RUN apk add --no-cache wget

# Upstream OpenTelemetry Java agent 2.x. Replaces the Elastic distro 1.4.1, which
# was too old to expose opentelemetry-api/context compatibly with Connector/J 9.7,
# so the driver could not propagate trace context to the MySQL server.
ARG OTEL_AGENT_VERSION=2.28.1
RUN wget -O /javaagent.jar https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar

# Verify download and set permissions
RUN chmod 644 /javaagent.jar
