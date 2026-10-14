package cn.xinji.agentplatform.security.product;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Immutable page evidence reader; Task and current-source authorization remain with the caller. */
final class ProductPatentSearchPages {
    record Prepared(ProductPatentSearchCursor.Context context, ProductPatentSearchExecution.Receipt receipt) { }
    private record Binding(String resultSetId, String methodVersion, long taskVersion,
                           String inputRevision, String scopeSha, java.time.Instant expiresAt) { }
    private final JdbcTemplate db;
    private final ProductPatentSearchEvidenceCodec evidence;
    private final ProductPatentSearchCursor cursors;
    private final ProductCollectedPatentTitles titles;
    private final java.util.Map<String,ProductSourceAvailability.Active> currentSources;
    ProductPatentSearchPages(JdbcTemplate db, ProductPatentSearchEvidenceCodec evidence,
            ProductPatentSearchCursor cursors, ProductCollectedPatentTitles titles,
            java.util.Map<String,ProductSourceAvailability.Active> currentSources) {
        this.db=ProductWatermarkJdbc.buffered(db); this.evidence=evidence; this.cursors=cursors;
        this.titles=titles; this.currentSources=java.util.Map.copyOf(currentSources);
    }

    ProductPatentSearchExecution.Page read(String resultSetId, int pageSize, long nextOrdinal,
            Prepared prepared) {
        var context = prepared.context();
        var receipt = prepared.receipt();
        if (!resultSetId.equals(context.resultSetId()) || !resultSetId.equals(receipt.resultSetId())
                || !context.methodVersion().equals(receipt.methodVersion()))
            throw new IllegalStateException("Committed search page binding differs");
        new ProductHistoricalResultIsolation(db).require(resultSetId);
        List<ProductPatentSearchExecution.Member> rows = members(db,evidence,resultSetId);
        boolean fieldResult=ProductPatentFieldResultEvidence.DIGEST_VERSION.equals(receipt.digestVersion());
        var fields=fieldResult ? evidence.fieldStore(db).read(resultSetId) : null;
        String digest=fieldResult ? ProductPatentFieldResultEvidence.digest(evidence,fields,rows,receipt.truncated())
                : evidence.resultDigest(rows,receipt.truncated());
        if ((!fieldResult && !"RESULT_DIGEST_DECIMAL_12/v2".equals(receipt.digestVersion()))
                || rows.size() != receipt.resultCount() || !receipt.resultSha().equals(digest))
            throw new IllegalStateException("Committed search result integrity differs");
        for (int ordinal = 0; ordinal < rows.size(); ordinal++)
            if (rows.get(ordinal).ordinal() != ordinal)
                throw new IllegalStateException("Committed search result integrity differs");
        int start = Math.toIntExact(Math.min(nextOrdinal,rows.size()));
        int end = Math.min(rows.size(),start+pageSize);
        String next = end<rows.size() ? cursors.issue(context,pageSize,end) : null;
        var members=List.copyOf(rows.subList(start,end));
        var citations=fieldResult ? titles.fieldCitations(members,currentSources,Set.copyOf(fields.fields()))
                : titles.citations(members,currentSources);
        ProductPatentFieldResultEvidence.Bundle pageEvidence=null;
        if(fieldResult) {
            java.util.Map<String,ProductPatentFieldResultEvidence.Fields> facts=new java.util.TreeMap<>();
            for(var member:members) facts.put(member.memberId(),fields.members().get(member.memberId()));
            if(citations.size()!=members.size()) throw new IllegalStateException("Field page citation count differs");
            for(int i=0;i<members.size();i++) {
                var citation=citations.get(i); var member=members.get(i);
                if(!member.memberId().equals(citation.memberId())
                        || !facts.get(member.memberId()).locators().equals(citation.fieldLocators()))
                    throw new IllegalStateException("Field page original locators differ from committed evidence");
            }
            pageEvidence=new ProductPatentFieldResultEvidence.Bundle(fields.version(),fields.fields(),fields.models(),
                    fields.method(),fields.ordering(),fields.rerankModel(),fields.rerankStatus(),facts);
        }
        return new ProductPatentSearchExecution.Page(resultSetId,receipt.attemptId(),receipt.methodVersion(),
                receipt.resultSha(),receipt.truncated(),receipt.candidateCount(),pageSize,next,
                receipt.confidenceStatus(),receipt.confidenceRuleVersion(),receipt.confidenceReason(),
                members,receipt.status(),receipt.totalReliability(),receipt.sourceOutcomes(),
                citations,receipt.digestVersion(),pageEvidence);
    }

    static List<ProductPatentSearchExecution.Member> members(JdbcTemplate db,
            ProductPatentSearchEvidenceCodec evidence, String resultSetId) {
        return members(db,evidence,resultSetId,false);
    }

    static List<ProductPatentSearchExecution.Member> members(JdbcTemplate db,
            ProductPatentSearchEvidenceCodec evidence, String resultSetId, boolean legacyAnd) {
        return db.query("SELECT member_id,ordinal,source_id,document_id,document_version,"
                        + "source_version,raw_sha,locator_ref,match_fields,matched_terms_json,lexical_score,"
                        + "field_scores_json,lexical_rank,semantic_score,semantic_rank,fusion_score,"
                        + "rerank_score,rerank_rank FROM con4_result_member WHERE result_set_id=? ORDER BY ordinal",
                (rs,row) -> new ProductPatentSearchExecution.Member(rs.getString(1),rs.getLong(2),rs.getString(3),
                        rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),
                        Set.of(rs.getString(9).split(",")),legacyAnd && rs.getString(10)==null
                                ?List.of():evidence.stringList(rs.getString(10)),
                        nullableDouble(rs,11),legacyAnd && rs.getString(12)==null
                                ?java.util.Map.of():evidence.scoreMap(rs.getString(12)),nullableInt(rs,13),
                        nullableDouble(rs,14),nullableInt(rs,15),nullableDouble(rs,16),
                        nullableDouble(rs,17),nullableInt(rs,18)),resultSetId);
    }

    Prepared prepare(String resultSetId, String subjectId) {
        List<Binding> rows=db.query("SELECT r.result_set_id,a.method_version,"
                        + "a.task_version,a.input_revision,a.scope_sha,a.expires_at FROM con4_result_set r "
                        + "JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id WHERE r.result_set_id=?",
                (rs,row)->new Binding(rs.getString(1),rs.getString(2),
                        rs.getLong(3),rs.getString(4),rs.getString(5),
                        rs.getTimestamp(6).toInstant()),resultSetId);
        if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var binding = rows.getFirst();
        var receipt = new ProductPatentSearchReceipts(db).read(resultSetId);
        var context = new ProductPatentSearchCursor.Context(binding.resultSetId(), binding.methodVersion(),
                subjectId, binding.taskVersion(), binding.inputRevision(), binding.scopeSha(),
                sourceEpochSha(receipt.sourceOutcomes()), binding.expiresAt());
        return new Prepared(context, receipt);
    }
    private String sourceEpochSha(List<ProductPatentSearchSourceBatch.Outcome> sources) {
        List<String> values = sources.stream().map(source -> source.sourceId() + "\u0000"
                + source.contractVersion() + "\u0000" + source.decisionId() + "\u0000" + source.sourceEpoch())
                .toList();
        if(values.isEmpty()) throw new IllegalStateException("Result source evidence missing");
        return evidence.digest(values.toArray(String[]::new));
    }
    private static Double nullableDouble(java.sql.ResultSet rows,int column) throws java.sql.SQLException {
        double value=rows.getDouble(column);return rows.wasNull()?null:value;
    }
    private static Integer nullableInt(java.sql.ResultSet rows,int column) throws java.sql.SQLException {
        int value=rows.getInt(column);return rows.wasNull()?null:value;
    }
}


