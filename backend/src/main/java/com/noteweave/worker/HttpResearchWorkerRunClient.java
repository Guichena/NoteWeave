package com.noteweave.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpResearchWorkerRunClient implements ResearchWorkerRunClient {

    private final RestClient restClient;
    private final String researchWorkerBaseUrl;

    public HttpResearchWorkerRunClient(
            RestClient.Builder restClientBuilder,
            @Value("${noteweave.workers.research.base-url:http://localhost:18091}") String researchWorkerBaseUrl
    ) {
        this.restClient = restClientBuilder.build();
        this.researchWorkerBaseUrl = researchWorkerBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void runTask(String taskId) {
        restClient.post()
                .uri(researchWorkerBaseUrl + "/tasks/{taskId}/run", taskId)
                .retrieve()
                .toBodilessEntity();
    }
}
