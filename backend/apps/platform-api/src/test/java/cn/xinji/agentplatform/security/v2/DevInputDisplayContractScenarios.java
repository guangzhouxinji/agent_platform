package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevInputDisplayContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final DevTaskCoreStore taskCore;
    private final DevTextResultStore textResults;

    DevInputDisplayContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc,
            DevTaskCoreStore taskCore, DevTextResultStore textResults) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.taskCore = taskCore;
        this.textResults = textResults;
    }

    void oldSaveCannotDisplay() throws Exception {
        String token = DevelopmentBoundaryPolicyTest.token("human-a");
        String task = mapper.readTree(mvc.perform(post("/v2/consultation/tasks")
                .header("Authorization", token).header("Idempotency-Key", "display-denied-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("taskId").asText();
        JsonNode result = mapper.readTree(mvc.perform(post("/v2/consultation/tasks/" + task + "/text-searches")
                .header("Authorization", token).header("Idempotency-Key", "display-search-" + task)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        int receipts = jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                Integer.class, task);
        var actor = new DevTaskCoreStore.Actor("dev-con-a", "dev-con-isolated", 1,
                Instant.parse("2099-01-01T00:00:00Z"));
        DevTaskCoreStore.Guard oldSaveOnly = new DevTaskCoreStore.Guard() {
            @Override public boolean humanCurrent(DevTaskCoreStore.Actor human, Instant now) { return true; }
            @Override public boolean inputAllowed(DevTaskCoreStore.Actor human, String kind,
                                                   String text, Instant now) { return true; }
            @Override public boolean inputDisplayAllowed(DevTaskCoreStore.Actor human, String kind,
                                                          String text, Instant now) { return false; }
            @Override public boolean delegationCurrent(String owner, String org, long version,
                                                       Instant until, Instant now) { return false; }
            @Override public boolean serviceCurrent(DevTaskCoreStore.Actor service,
                    DevTaskCoreStore.ServiceRole role, Instant now) { return false; }
        };
        assertThatThrownBy(() -> taskCore.read(actor, task, oldSaveOnly))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThatThrownBy(() -> textResults.prepareEvidence(actor, task, result.path("setId").asText(),
                1, oldSaveOnly, (human, id, version, digest, query, save, display, now) -> null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                Integer.class, task)).isEqualTo(receipts);
    }
}
