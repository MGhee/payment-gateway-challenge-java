# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 gateway
USER gateway
WORKDIR /app
COPY --from=build /app/build/libs/payment-gateway.jar app.jar
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s \
  CMD curl -fs http://localhost:8090/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
