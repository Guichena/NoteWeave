FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./

RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/

RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /workspace/target/noteweave-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 18082

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
