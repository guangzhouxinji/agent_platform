package cn.xinji.agentplatform.modelconfig;

import cn.xinji.agentplatform.config.ConsultationAgentModelProperties;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry.RegisteredModel;
import cn.xinji.agentplatform.consultation.answer.adapter.out.openai.OpenAiConsultationAnswerAdapter.ModelEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagedModelConfigurationService implements ConsultationAgentModelRegistry {
    private static final String CHANNEL = "PUBLIC_LOCAL";
    private final ObjectProvider<JdbcTemplate> templates;
    private final ConsultationAgentModelProperties fallback;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String encodedMasterKey;
    private final String adminRequester;

    public ManagedModelConfigurationService(ObjectProvider<JdbcTemplate> templates,
            ConsultationAgentModelProperties fallback, ObjectMapper mapper,
            @Value("${model-configuration.master-key:}") String encodedMasterKey,
            @Value("${model-configuration.admin-requester:v0-authorized-user}") String adminRequester) {
        this.templates = templates;
        this.fallback = fallback;
        this.mapper = mapper;
        this.encodedMasterKey = encodedMasterKey;
        this.adminRequester = adminRequester;
    }

    public void authorize(String requester) {
        if (!adminRequester.equals(requester)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前身份无权管理模型配置");
    }

    public List<ModelView> list() {
        return database().query("SELECT model_id,display_name,provider,model_name,endpoint,enabled,priority_no,data_channel,updated_at,last_test_status,last_tested_at,last_test_error_code FROM con_model_configuration ORDER BY priority_no",
                (rs, row) -> new ModelView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6) == 1, rs.getInt(7), rs.getString(8), true,
                        rs.getTimestamp(9).toInstant(), rs.getString(10),
                        rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), rs.getString(12)));
    }

    @Transactional
    public ModelView save(String modelId, SaveModel input, String requester) {
        validate(modelId, input);
        JdbcTemplate jdbc = database();
        StoredSecret previous = jdbc.query("SELECT encrypted_api_key,encryption_nonce FROM con_model_configuration WHERE model_id=?",
                rs -> rs.next() ? new StoredSecret(rs.getString(1), rs.getString(2)) : null, modelId);
        StoredSecret secret = previous;
        if (input.apiKey() != null && !input.apiKey().isBlank()) {
            ModelSecretCipher.Encrypted encrypted = cipher().encrypt(input.apiKey().trim());
            secret = new StoredSecret(encrypted.value(), encrypted.nonce());
        }
        if (secret == null) throw badRequest("新模型必须提供 API Key");
        if (input.enabled()) decrypt(secret);
        Integer occupied = jdbc.queryForObject("SELECT COUNT(*) FROM con_model_configuration WHERE priority_no=? AND model_id<>?",
                Integer.class, input.priority(), modelId);
        if (occupied != null && occupied > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "调用优先级已存在");
        Instant now = Instant.now();
        int updated = jdbc.update("UPDATE con_model_configuration SET display_name=?,provider=?,model_name=?,endpoint=?,encrypted_api_key=?,encryption_nonce=?,enabled=?,priority_no=?,data_channel=?,change_note=?,updated_by=?,updated_at=?,last_test_status=NULL,last_tested_at=NULL,last_test_error_code=NULL WHERE model_id=?",
                clean(input.displayName()), clean(input.provider()), clean(input.modelName()), clean(input.endpoint()),
                secret.encrypted(), secret.nonce(), input.enabled() ? 1 : 0, input.priority(), CHANNEL,
                clean(input.changeNote()), requester, Timestamp.from(now), modelId);
        if (updated == 0) jdbc.update("INSERT INTO con_model_configuration(model_id,display_name,provider,model_name,endpoint,encrypted_api_key,encryption_nonce,enabled,priority_no,data_channel,change_note,updated_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                modelId, clean(input.displayName()), clean(input.provider()), clean(input.modelName()), clean(input.endpoint()),
                secret.encrypted(), secret.nonce(), input.enabled() ? 1 : 0, input.priority(), CHANNEL,
                clean(input.changeNote()), requester, Timestamp.from(now), Timestamp.from(now));
        audit(jdbc, modelId, updated == 0 ? "CREATED" : "UPDATED", input.enabled(), input.priority(), null, null,
                input.changeNote(), requester, now);
        return find(modelId);
    }

    @Transactional
    public List<ModelView> reorder(List<String> modelIds, String changeNote, String requester) {
        if (modelIds == null || modelIds.isEmpty() || modelIds.stream().anyMatch(id -> id == null || id.isBlank())
                || modelIds.stream().distinct().count() != modelIds.size()) throw badRequest("排序必须包含不重复的模型编号");
        JdbcTemplate jdbc = database();
        List<String> existing = jdbc.query("SELECT model_id FROM con_model_configuration ORDER BY priority_no", (rs, row) -> rs.getString(1));
        if (!existing.stream().sorted().toList().equals(modelIds.stream().sorted().toList())) throw badRequest("排序必须包含全部模型且不能增加未知模型");
        String note = required(changeNote, "变更说明");
        Instant now = Instant.now();
        for (int i = 0; i < modelIds.size(); i++) jdbc.update("UPDATE con_model_configuration SET priority_no=?,updated_by=?,updated_at=?,change_note=? WHERE model_id=?",
                10000 + i, requester, Timestamp.from(now), note, modelIds.get(i));
        for (int i = 0; i < modelIds.size(); i++) {
            jdbc.update("UPDATE con_model_configuration SET priority_no=? WHERE model_id=?", i + 1, modelIds.get(i));
            ModelView model = find(modelIds.get(i));
            audit(jdbc, model.modelId(), "REORDERED", model.enabled(), i + 1, null, null, note, requester, now);
        }
        return list();
    }

    /** Applies a saved agent draft without changing any model's enablement or credentials. */
    @Transactional
    public List<ModelView> applyDraftOrder(List<String> draftModelIds, long draftVersion, String requester) {
        if (draftModelIds == null || draftModelIds.isEmpty()
                || draftModelIds.stream().anyMatch(id -> id == null || id.isBlank())
                || draftModelIds.stream().distinct().count() != draftModelIds.size()) {
            throw badRequest("草稿必须包含不重复的已保存模型");
        }
        List<String> currentOrder = list().stream().map(ModelView::modelId).toList();
        if (currentOrder.isEmpty()) throw badRequest("尚未保存实际运行模型，无法应用草稿");
        if (!currentOrder.containsAll(draftModelIds)) {
            throw badRequest("草稿包含未保存的实际运行模型，请保存草稿后刷新模型目录再试");
        }
        List<String> appliedOrder = new ArrayList<>(draftModelIds);
        currentOrder.stream().filter(modelId -> !appliedOrder.contains(modelId)).forEach(appliedOrder::add);
        return reorder(appliedOrder, "应用咨询智能体草稿版本 " + draftVersion, requester);
    }

    public ModelView test(String modelId, String requester) {
        JdbcTemplate jdbc = database();
        ResolvedModel model = managed(modelId);
        String status = "SUCCEEDED";
        String error = null;
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", model.modelName());
            payload.put("max_tokens", 8);
            payload.putArray("messages").addObject().put("role", "user").put("content", "Reply with OK.");
            HttpRequest request = HttpRequest.newBuilder(model.endpoint()).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + model.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code < 200 || code >= 300) { status = "FAILED"; error = sanitizedStatus(code); }
        } catch (java.net.http.HttpTimeoutException exception) { status = "FAILED"; error = "TIMEOUT";
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); status = "FAILED"; error = "INTERRUPTED";
        } catch (Exception exception) { status = "FAILED"; error = "UPSTREAM_UNAVAILABLE"; }
        Instant now = Instant.now();
        jdbc.update("UPDATE con_model_configuration SET last_test_status=?,last_tested_at=?,last_test_error_code=? WHERE model_id=?",
                status, Timestamp.from(now), error, modelId);
        ModelView view = find(modelId);
        audit(jdbc, modelId, "TESTED", view.enabled(), view.priority(), status, error, "固定无业务数据连接测试", requester, now);
        return view;
    }

    @Override
    public List<RegisteredModel> models() {
        try {
            List<ModelView> managed = list();
            if (!managed.isEmpty()) return managed.stream().map(model -> new RegisteredModel(model.modelId(), model.provider(),
                    model.modelName(), model.displayName(), model.enabled(), model.enabled() && masterKeyAvailable(), CHANNEL,
                    model.enabled() ? masterKeyAvailable() ? "后端配置完整" : "模型主密钥未配置" : "模型已停用")).toList();
        } catch (RuntimeException ignored) { }
        return fallbackModels();
    }

    public List<String> runtimeModelChain(List<String> fallbackChain) {
        try {
            List<ModelState> states = database().query("SELECT model_id,enabled FROM con_model_configuration ORDER BY priority_no",
                    (rs, row) -> new ModelState(rs.getString(1), rs.getInt(2) == 1));
            if (!states.isEmpty()) return states.stream().filter(ModelState::enabled).map(ModelState::modelId).toList();
        } catch (DataAccessException | ResponseStatusException ignored) { }
        return fallbackChain;
    }

    public boolean hasManagedModels() {
        try {
            Integer count = database().queryForObject("SELECT COUNT(*) FROM con_model_configuration", Integer.class);
            return count != null && count > 0;
        } catch (DataAccessException | ResponseStatusException ignored) {
            return false;
        }
    }

    public ModelEndpoint endpoint(String modelId) {
        if (templates.getIfAvailable() == null) return fallbackEndpoint(modelId);
        try {
            ResolvedModel model = managedOrNull(modelId);
            if (model == null) return fallbackEndpoint(modelId);
            return new ModelEndpoint(model.endpoint(), model.apiKey(), model.modelName());
        } catch (DataAccessException databaseUnavailable) {
            return fallbackEndpoint(modelId);
        }
    }

    private ModelEndpoint fallbackEndpoint(String modelId) {
        var model = fallback.getModels().values().stream().filter(value -> modelId.equals(value.getModelId())).findFirst().orElse(null);
        return model == null || model.getEndpoint() == null ? null
                : new ModelEndpoint(URI.create(model.getEndpoint().trim()), value(model.getApiKey()), model.getModelName().trim());
    }

    private ResolvedModel managed(String modelId) {
        ResolvedModel model = managedOrNull(modelId);
        if (model == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在");
        return model;
    }

    private ResolvedModel managedOrNull(String modelId) {
        return database().query("SELECT endpoint,model_name,encrypted_api_key,encryption_nonce,enabled FROM con_model_configuration WHERE model_id=?",
                rs -> {
                    if (!rs.next()) return null;
                    if (rs.getInt(5) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "停用模型不能测试或调用");
                    return new ResolvedModel(URI.create(rs.getString(1)), rs.getString(2),
                            decrypt(new StoredSecret(rs.getString(3), rs.getString(4))));
                }, modelId);
    }

    private ModelView find(String modelId) {
        return list().stream().filter(model -> model.modelId().equals(modelId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在"));
    }

    private List<RegisteredModel> fallbackModels() {
        List<RegisteredModel> result = new ArrayList<>();
        fallback.getModels().values().forEach(model -> {
            boolean configured = model.isEnabled() && model.getApiKey() != null && !model.getApiKey().isBlank();
            result.add(new RegisteredModel(model.getModelId(), model.getProvider(), model.getModelName(), model.getDisplayName(),
                    model.isEnabled(), configured, model.getDataChannel(), configured ? "后端配置完整" : model.isEnabled() ? "服务端密钥尚未配置" : "模型已停用"));
        });
        return List.copyOf(result);
    }

    private void audit(JdbcTemplate jdbc, String modelId, String action, boolean enabled, int priority,
            String status, String error, String note, String requester, Instant now) {
        AuditIdentity identity = jdbc.query("SELECT provider,model_name,endpoint FROM con_model_configuration WHERE model_id=?",
                rs -> rs.next() ? new AuditIdentity(rs.getString(1), rs.getString(2), rs.getString(3)) : null, modelId);
        if (identity == null) throw new IllegalStateException("Cannot audit missing model configuration");
        jdbc.update("INSERT INTO con_model_configuration_audit(audit_id,model_id,provider,model_name,endpoint,action_type,enabled,priority_no,test_status,test_error_code,change_note,changed_by,changed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), modelId, identity.provider(), identity.modelName(), identity.endpoint(),
                action, enabled ? 1 : 0, priority, status, error, clean(note), requester, Timestamp.from(now));
    }

    private JdbcTemplate database() {
        JdbcTemplate jdbc = templates.getIfAvailable();
        if (jdbc == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型配置数据库未连接");
        return jdbc;
    }
    private ModelSecretCipher cipher() {
        try { return ModelSecretCipher.fromEnvironment(encodedMasterKey); }
        catch (IllegalStateException exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型密钥服务未配置"); }
    }
    private String decrypt(StoredSecret secret) {
        try { return cipher().decrypt(secret.encrypted(), secret.nonce()); }
        catch (ResponseStatusException exception) { throw exception; }
        catch (IllegalStateException exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型密钥服务不可用"); }
    }
    private boolean masterKeyAvailable() {
        try { Base64.getDecoder().decode(encodedMasterKey); return !encodedMasterKey.isBlank() && Base64.getDecoder().decode(encodedMasterKey).length == 32; }
        catch (RuntimeException exception) { return false; }
    }
    private static void validate(String id, SaveModel value) {
        required(id, "模型编号"); required(value.displayName(), "显示名称"); required(value.provider(), "供应商");
        required(value.modelName(), "模型名称"); String endpoint = required(value.endpoint(), "接口地址"); required(value.changeNote(), "变更说明");
        if (value.priority() < 1) throw badRequest("优先级必须大于 0");
        try { URI uri = URI.create(endpoint); boolean local = "http".equalsIgnoreCase(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())); if (!"https".equalsIgnoreCase(uri.getScheme()) && !local) throw new IllegalArgumentException(); }
        catch (Exception exception) { throw badRequest("接口地址必须为 HTTPS 或本地 HTTP 地址"); }
    }
    private static String sanitizedStatus(int code) { return code == 401 || code == 403 ? "AUTHENTICATION_FAILED" : code == 429 ? "RATE_LIMITED" : code >= 500 ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_REJECTED"; }
    private static String required(String value, String label) { if (value == null || value.isBlank()) throw badRequest(label + "不能为空"); return value.trim(); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record SaveModel(String displayName, String provider, String modelName, String endpoint, String apiKey,
                            boolean enabled, int priority, String changeNote) {}
    public record ReorderModels(List<String> modelIds, String changeNote) {}
    public record ModelView(String modelId, String displayName, String provider, String modelName, String endpoint,
                            boolean enabled, int priority, String dataChannel, boolean credentialConfigured,
                            Instant updatedAt, String lastTestStatus, Instant lastTestedAt, String lastTestErrorCode) {}
    private record StoredSecret(String encrypted, String nonce) {}
    private record ResolvedModel(URI endpoint, String modelName, String apiKey) {}
    private record ModelState(String modelId, boolean enabled) {}
    private record AuditIdentity(String provider, String modelName, String endpoint) {}
}
