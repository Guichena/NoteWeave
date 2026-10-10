FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# 可选的 Maven JVM 参数，例如构建需要走代理时传入 -Dhttps.proxyHost=...
ARG MAVEN_OPTS

COPY pom.xml ./
COPY maven-settings.xml ./maven-settings.xml
RUN mvn -s maven-settings.xml -B -ntp -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline

COPY src ./src
# Runtime-image builds validate production sources.  Test compilation belongs
# to the explicit test gates, so unrelated in-progress test edits cannot block
# an isolated integration fixture from starting the application.
RUN mvn -s maven-settings.xml -B -ntp -Dmaven.wagon.http.retryHandler.count=5 package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/*.jar /app/noteweave-backend.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/noteweave-backend.jar"]
