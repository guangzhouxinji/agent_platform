package cn.xinji.agentplatform.enforcement.legalreference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

public final class LegalReferenceTargetStore implements LegalReferenceService.Target {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public LegalReferenceTargetStore(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    @Override public String require(String actor, String organization, String type, String id,
                                    long revision, String anchor, String purpose) {
        String query = switch (type) {
            case "CONDUCT_REPORT" -> "SELECT organization_id,payload FROM enf7_conduct_report "
                    + "WHERE analysis_id=? AND revision=?";
            case "PROCEDURE_REPORT" -> "SELECT organization_id,payload FROM enf6_procedure_report "
                    + "WHERE report_id=? AND revision=?";
            default -> throw new SecurityException("Legal target type unavailable");
        };
        var rows = db.query(query,
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), id, revision);
        if (rows.size() != 1 || !organization.equals(rows.getFirst().organization()))
            throw new SecurityException("Legal target unavailable");
        try {
            JsonNode node = json.readTree(rows.getFirst().payload());
            for (String part : anchor.replaceAll("\\[(\\d+)]", ".$1").split("\\.")) {
                if (!part.isBlank()) node = part.chars().allMatch(Character::isDigit)
                        ? node.path(Integer.parseInt(part)) : node.path(part);
            }
            if (node.isMissingNode() || node.isNull()) throw new IllegalArgumentException("Target anchor unavailable");
            return LegalReferenceService.hash(json.writeValueAsString(node));
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Legal target unavailable", failure); }
    }
    private record Stored(String organization, String payload) { }
}
