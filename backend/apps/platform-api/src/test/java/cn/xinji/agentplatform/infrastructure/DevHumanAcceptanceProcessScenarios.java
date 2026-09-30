package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;

final class DevHumanAcceptanceProcessScenarios {
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface interface Request {
        HttpResponse<String> send(String method, String path, String body, String key, String actor) throws Exception;
    }

    static void accepted(String task, Request request) throws Exception {
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        var current = request.send("GET", route + "/current", null, null, "human-a");
        String id = JSON.readTree(current.body()).path("request").path("requestId").asText();
        var queue = request.send("GET", "/v2/consultation/human-requests/pending", null, null, "human-b");
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains(id).doesNotContain("请人工核对");
        assertThat(request.send("GET", "/v2/consultation/tasks/" + task, null, null, "human-b").statusCode())
                .isEqualTo(404);
        var result = request.send("POST", route + "/1/accept", "{\"expectedTaskRevision\":2,"
                + "\"expectedRequestSequence\":1,\"requestId\":\"" + id + "\"}",
                "process-human-accept-12345", "human-b");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(result.body()).path("acceptedBy").asText()).isEqualTo("dev-con-b");
        assertThat(JSON.readTree(request.send("GET", route + "/current", null, null, "human-a")
                .body()).path("request").path("acceptanceState").asText()).isEqualTo("ACCEPTED");
        var opinion = request.send("POST", route + "/1/opinion", "{\"expectedTaskRevision\":2,"
                + "\"expectedRequestSequence\":1,\"requestId\":\"" + id
                + "\",\"opinionInputId\":\"OPINION_GAP\"}", "process-human-opinion-12345", "human-b");
        assertThat(opinion.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(opinion.body()).path("submittedBy").asText()).isEqualTo("dev-con-b");
    }

    static void restored(String task, Request request) throws Exception {
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        var current = request.send("GET", route + "/current", null, null, "human-a");
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(current.body()).path("request").path("acceptanceState").asText())
                .isEqualTo("ACCEPTED");
        var opinion = request.send("GET", route + "/1/opinion", null, null, "human-a");
        assertThat(opinion.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(opinion.body()).path("submittedBy").asText()).isEqualTo("dev-con-b");
        assertThat(request.send("POST", route + "/1/opinion", "{}", "opinion-disabled-12345",
                "human-b").statusCode()).isEqualTo(404);
        assertThat(request.send("GET", "/v2/consultation/human-requests/pending", null, null,
                "human-b").statusCode()).isEqualTo(404);
    }
}
