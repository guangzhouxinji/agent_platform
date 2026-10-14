package cn.xinji.agentplatform.security.product;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
/** Owned coordination input only; no source admission/licensing or business acceptance proof. */
final class SharedDm8FaultSourceInput {
    static final String SOURCE="owned-coordination-source", CONTRACT="owned-contract";
    static final String DECISION="11111111-1111-4111-8111-111111111111", HASH="a".repeat(64);
    static void contract(JdbcTemplate db, Instant now) {
        db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,retention_version,retention_evidence_sha,collected_at,valid_until,status,row_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", SOURCE, CONTRACT,"owned-engineering","{}",HASH,"owned-ref",HASH,"owned-retention",1,HASH,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),"ACTIVE",2,Timestamp.from(now));
    }
    static void admit(JdbcTemplate db, Instant now, ProductSourceWatermark.Intent intent) {
        db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,"
                        + "declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,"
                        + "retention_version,retention_evidence_sha,collected_at,valid_until,status,"
                        + "row_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", SOURCE,
                CONTRACT, "observed-1", "{}", HASH, "rights", HASH, "retention", 1, HASH,
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                "ACTIVE", 2, Timestamp.from(now));
        db.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,rights_ref,"
                        + "rights_sha,capability_ref,capability_sha,responsible_party,maintainer_ref,"
                        + "coverage_sha,valid_until,decided_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", DECISION,
                SOURCE, CONTRACT, "rights", HASH, "capability", HASH, "responsible", "maintainer",
                HASH, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));
        db.update("INSERT INTO con4_source_product_decision(decision_id,decision_version,"
                + "decision_ref,decision_sha,rights_evidence_status,organization_id,task_id,"
                + "scope_id,retention_ref,retention_version,retention_evidence_sha) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", DECISION, "v1", "decision.json", HASH,
                "UNRESOLVED", "organization", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "retention", 1, HASH);
        db.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,valid_until) "
                + "VALUES(?,?,?,?)", DECISION, "TITLE", "QUERY", Timestamp.from(now.plusSeconds(3600)));
        db.update("INSERT INTO con4_source_current(source_id,contract_version,decision_id,source_epoch,"
                        + "status,row_version,history_sha,updated_at) VALUES(?,?,?,1,'ACTIVE',1,?,?)",
                SOURCE, CONTRACT, DECISION, "0".repeat(64), Timestamp.from(now));
        event(db, "ADMIT", 0, 1, 0, 1, intent, now);
    }

    static void event(JdbcTemplate db, String operation, long beforeEpoch, long afterEpoch,
            long beforeVersion, long afterVersion, ProductSourceWatermark.Intent intent, Instant now) {
        db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,"
                        + "decision_id,operation,before_epoch,after_epoch,before_version,after_version,"
                        + "intent_id,before_history_sha,actor_ref,request_sha,occurred_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), SOURCE,
                CONTRACT, DECISION, operation, beforeEpoch, afterEpoch, beforeVersion, afterVersion,
                intent.id(), intent.beforeHistorySha(), "maintainer", HASH, Timestamp.from(now));
    }

}
