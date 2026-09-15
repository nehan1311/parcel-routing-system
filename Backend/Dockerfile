FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/target/parcel-routing-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -Dserver.address=0.0.0.0 -Dserver.port=${PORT:-${SERVER_PORT:-8080}} -jar /app/app.jar"]
