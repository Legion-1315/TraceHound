# One image for every estate service; the Spring profile picks the role at runtime
# (SPRING_PROFILES_ACTIVE=trade-capture, ref-data-service, ...).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
# Small heaps: seven of these share one machine with the triage app.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+UseSerialGC", "-jar", "app.jar"]
