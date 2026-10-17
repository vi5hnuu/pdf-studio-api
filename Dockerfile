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

# MaxRAMPercentage, because the default is 25% of whatever the container can see — and the
# compose file sets no memory limit, so that is 25% of the whole host. The tools buffer their
# whole output in heap (ByteArrayResource over a ByteArrayOutputStream), so the ceiling decides
# whether an overload is a 503 from the admission limiter or an OOM kill and a restart loop.
# ExitOnOutOfMemoryError makes the second case fail fast and visibly rather than limping.
CMD ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
