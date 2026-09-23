package cn.xinji.agentplatform.enforcement.adapter.out.synthetic;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.ConnectionCapabilities;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceConfiguration;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public final class DevelopmentEnforcementSourceAdapter implements SourceAdapter {
    @Override
    public ConnectionCapabilities test(SourceConfiguration source) {
        if (!"local://enforcement/development-feed".equals(source.controlledEndpoint())) {
            throw new IllegalStateException("SOURCE_ADAPTER_NOT_ALLOWED");
        }
        return new ConnectionCapabilities("PASSED", "PASSED", "PASSED", "PASSED", "PASSED", null);
    }

    @Override
    public List<SourceRecord> fetch(SourceConfiguration source, String cursor) {
        test(source);
        Instant base = Instant.parse("2026-09-20T00:00:00Z");
        return List.of(
                record(1, "DEV-LEAD-001", "v1", base, "投诉举报开发记录：商品标识待人工核实",
                        "投诉人", "甲方科技有限公司", "销售方", "乙方商贸中心", "91310000TEST000001",
                        "被投诉商品包装标注专利字样", "2026-09-19", "2026-09-19", "某市某区市场",
                        "EVIDENCE-DEV-001"),
                record(2, "DEV-LEAD-002", "v1", base.plusSeconds(60), "监管监测开发记录：来源范围有限",
                        "经营者", "丙方网络店铺", null, null, null,
                        "页面展示未经核实的权利标识", "2026年9月19日", null, "某网络交易平台",
                        "EVIDENCE-DEV-002"),
                record(3, "DEV-LEAD-003", "v1", base.plusSeconds(120), "公开页面开发记录：非专业证据",
                        "发布者", "丁方信息服务部", null, null, null,
                        "公开页面含待核实宣传内容", "2026-09-20T08:00:00+08:00", "2026-09-20T08:00:00+08:00",
                        "某市线上页面", "EVIDENCE-DEV-003")
        );
    }

    private static SourceRecord record(int sequence, String id, String version, Instant updatedAt, String content,
                                       String role, String name, String secondRole, String secondName, String creditCode,
                                       String fact, String rawTime, String normalizedTime, String place, String evidenceRef) {
        String secondSubject = secondRole == null ? "" : ",{" +
                "\"role\":\"" + secondRole + "\",\"name\":\"" + secondName + "\",\"creditCode\":\"" + creditCode + "\"}";
        String normalized = normalizedTime == null ? "" : ",\"normalizedValue\":\"" + normalizedTime + "\"";
        String payload = "{\"dataMode\":\"SYNTHETIC_LOCAL\",\"professionalUse\":\"NOT_PROFESSIONAL_EVIDENCE\"," 
                + "\"sourceRecordId\":\"" + id + "\",\"sourceVersion\":\"" + version
                + "\",\"sourceUpdatedAt\":\"" + updatedAt + "\",\"content\":\"" + content + "\"," 
                + "\"subjects\":[{\"role\":\"" + role + "\",\"name\":\"" + name + "\"}" + secondSubject + "],"
                + "\"facts\":[{\"rawValue\":\"" + fact + "\"}],"
                + "\"eventTimes\":[{\"rawValue\":\"" + rawTime + "\"" + normalized + "}],"
                + "\"places\":[{\"rawValue\":\"" + place + "\",\"normalizedValue\":\"" + place + "\"}],"
                + "\"evidenceRefs\":[{\"referenceId\":\"" + evidenceRef + "\"}]}";
        return new SourceRecord(sequence, id, version, updatedAt, payload, sha256(payload), "AVAILABLE",
                Integer.toString(sequence));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("CONTENT_DIGEST_FAILURE", failure);
        }
    }
}
