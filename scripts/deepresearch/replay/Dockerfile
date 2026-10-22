FROM noteweave-backend-build-table-search AS java_tools

FROM noteweave-research-worker-consumer
COPY --from=java_tools /opt/java/openjdk /opt/java/openjdk
COPY --from=java_tools /root/.m2/repository /root/.m2/repository
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN java -version && python --version
