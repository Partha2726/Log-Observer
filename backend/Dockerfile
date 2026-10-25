# Foundation-level Dockerfile. Simple multi-stage build; no production
# hardening (non-root user, distroless, etc.) has been added yet — that
# belongs to a later hardening pass (Account 08), not the Foundation
# layer.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/platform-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
