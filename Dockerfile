# Build stage
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x ./mvnw && ./mvnw -B -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=builder /build/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
