# -------------------------
# Stage 1: Build
# -------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first (cache-friendly)
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline

# Copy source code
COPY src src

# Build application
RUN mvn package -DskipTests


# -------------------------
# Stage 2: Runtime
# -------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/target/pdf-studio-api.jar app.jar

EXPOSE 8082

CMD ["java", "-jar", "app.jar"]
