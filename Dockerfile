FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl \
    && addgroup -S jitong \
    && adduser -S jitong -G jitong

WORKDIR /app
COPY --from=build /workspace/target/im-server-0.1.0-SNAPSHOT.jar /app/server.jar

USER jitong
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/server.jar"]
