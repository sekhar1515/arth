# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/arth-backend-*.jar /app.jar

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S arth && adduser -S arth -G arth
USER arth
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
