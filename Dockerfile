FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public

COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./

RUN printf '%s\n' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"' \
    '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' \
    '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>docker-build-mirror</id>' \
    '      <mirrorOf>*</mirrorOf>' \
    "      <url>${MAVEN_MIRROR_URL}</url>" \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' \
    > /tmp/maven-settings.xml

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN mvn -s /tmp/maven-settings.xml -q -DskipTests dependency:go-offline

COPY src/ src/

RUN mvn -s /tmp/maven-settings.xml -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /workspace/target/noteweave-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 18082

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
