# Stage 1: Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the application to produce the shaded jar
RUN mvn clean package

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the shaded jar from the build stage
COPY --from=build /app/target/chat-server.jar ./chat-server.jar

# Expose the WebSocket port
EXPOSE 8887

# Run the jar file
ENTRYPOINT ["java", "-jar", "chat-server.jar"]
