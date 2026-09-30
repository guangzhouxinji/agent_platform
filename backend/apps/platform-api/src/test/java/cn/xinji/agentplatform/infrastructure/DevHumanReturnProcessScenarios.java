package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;

final class DevHumanReturnProcessScenarios {
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface interface Request {
        HttpResponse<String> send(String method, String path, String body, String key, String actor) throws Exception;
    }

    static String returned(Request request) throws Exception {
        var task = request.send("POST", "/v2/consultation/tasks",
                "{\"goal\":\"查找并比较用于温度调节的传感器、控制器和阀门方案\"}",
                "process-return-create-12345", "human-a");
        assertThat(task.statusCode()).isEqualTo(201);
        String taskId = JSON.readTree(task.body()).path("taskId").asText();
        String route = "/v2/consultation/tasks/" + taskId + "/human-requests";
        var created = request.send("POST", route, "{\"expectedTaskRevision\":1,"
                + "\"expectedRequestSequence\":0,\"requestInputId\":\"REQUEST_GAP\"}",
                "process-return-request-12345", "human-a");
        assertThat(created.statusCode()).isEqualTo(200);
        String id = JSON.readTree(created.body()).path("requestId").asText();
        String body = "{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,\"requestId\":\""
                + id + "\"}";
        assertThat(request.send("POST", route + "/1/accept", body, "process-return-accept-12345",
                "human-b").statusCode()).isEqualTo(200);
        var result = request.send("POST", route + "/1/return", body,
                "process-return-submit-12345", "human-b");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(result.body()).path("returnedBy").asText()).isEqualTo("dev-con-b");
        assertThat(request.send("GET", route + "/1/opinion", null, null, "human-a").statusCode())
                .isEqualTo(404);
        return taskId;
    }

    static void restored(String taskId, Request request) throws Exception {
        String route = "/v2/consultation/tasks/" + taskId + "/human-requests/1";
        var result = request.send("GET", route + "/return", null, null, "human-a");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(result.body()).path("returnedBy").asText()).isEqualTo("dev-con-b");
        assertThat(request.send("GET", route, null, null, "human-a").body()).contains("ACCEPTED");
        assertThat(request.send("POST", route + "/return", "{}", "disabled-return-12345",
                "human-b").statusCode()).isEqualTo(404);
    }
}
