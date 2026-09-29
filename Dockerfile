# ---- Build stage: compile and package the framework with Maven ----
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- Runtime stage: only the JRE and the jar ----
FROM amazoncorretto:21
WORKDIR /usrapp/bin

ENV PORT=8080 \
    APP_ENV=production \
    GREETING_PREFIX=Hello \
    THREAD_POOL_SIZE=10 \
    SHUTDOWN_TIMEOUT_SECONDS=8

COPY --from=build /build/target/webframework.jar ./webframework.jar

EXPOSE 8080

# Exec form: java runs as PID 1 and receives the SIGTERM sent by `docker stop`,
# which triggers the framework's graceful-shutdown hook.
ENTRYPOINT ["java", "-jar", "webframework.jar"]
