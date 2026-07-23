# Single-image monolith: build the React app, fold it into the Spring Boot jar,
# and serve UI + REST + WebSocket from one process. Cloud Run ready.

FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src
COPY backend/ backend/
# The UI is already built above, so skip the npm work inside the Gradle build;
# processResources still folds this dist/ into the jar's static resources.
COPY --from=frontend /src/frontend/dist frontend/dist
WORKDIR /src/backend
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -q -PskipFrontend

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /src/backend/build/libs/*.jar app.jar
# Cloud Run injects PORT and expects the container to listen on it;
# application.properties reads ${PORT} and falls back to 8080 locally.
ENV PORT=8080
EXPOSE 8080
# Honour the container memory limit rather than the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
