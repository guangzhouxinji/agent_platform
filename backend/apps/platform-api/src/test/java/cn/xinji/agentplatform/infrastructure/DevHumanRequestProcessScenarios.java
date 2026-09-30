package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;

final class DevHumanRequestProcessScenarios {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BODY = "{\"expectedTaskRevision\":2,\"expectedRequestSequence\":0,"
            + "\"requestInputId\":\"REQUEST_GAP\"}";
    private static final String KEY = "process-human-request-12345";

    @FunctionalInterface interface Request {
        HttpResponse<String> send(String method, String path, String body, String key) throws Exception;
    }

    static void created(String task, Request request) throws Exception {
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        var response = request.send("POST", route, BODY, KEY);
        assertThat(response.statusCode()).isEqualTo(200);
        var saved = JSON.readTree(response.body());
        assertThat(saved.path("requestSequence").asLong()).isEqualTo(1);
        assertThat(saved.path("inputRevision").asLong()).isEqualTo(2);
        assertThat(saved.path("acceptanceState").asText()).isEqualTo("NOT_ACCEPTED");
        assertThat(request.send("GET", route + "/current", null, null).statusCode()).isEqualTo(200);
    }

    static void restored(String task, Request request) throws Exception {
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        var current = request.send("GET", route + "/current", null, null);
        assertThat(current.statusCode()).isEqualTo(200);
        var saved = JSON.readTree(current.body()).path("request");
        assertThat(saved.path("requestSequence").asLong()).isEqualTo(1);
        assertThat(saved.path("acceptanceState").asText()).isEqualTo("ACCEPTED");
        assertThat(request.send("GET", route + "/1", null, null).statusCode()).isEqualTo(200);
        var replay = request.send("POST", route, BODY, KEY);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(replay.body()).path("requestId").asText()).isEqualTo(saved.path("requestId").asText());
    }
}
