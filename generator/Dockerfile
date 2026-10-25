# One-shot generator image (spec section 14.1, profile "generate").
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/generator.jar /app/generator.jar
COPY --from=build /workspace/target/lib /app/lib
ENTRYPOINT ["java", "-jar", "/app/generator.jar"]
