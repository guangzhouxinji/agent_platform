package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

/** Real DM8 adapter: CAS, immutable artifact, receipt and event share one transaction. */
public final class Dm8DocumentDraftStore implements Store {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final Path root;
    public Dm8DocumentDraftStore(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Clock clock,Path root) {
        this.db=db;this.tx=tx;this.json=json;this.clock=clock;this.root=root.toAbsolutePath().normalize();
    }
    @Override public Optional<Draft> read(String id,long revision) {
        return db.query("SELECT payload,artifact_key,artifact_sha256 FROM enf12_document_draft_revision WHERE draft_id=? AND revision=?",
                (rs,n)->decode(rs.getString(1),rs.getString(2),rs.getString(3)),id,revision).stream().findFirst();
    }
    @Override public List<Draft> history(String id) {
        return db.query("SELECT payload,artifact_key,artifact_sha256 FROM enf12_document_draft_revision WHERE draft_id=? ORDER BY revision",
                (rs,n)->decode(rs.getString(1),rs.getString(2),rs.getString(3)),id);
    }
    @Override public void append(Draft draft,long expected) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Draft command transaction required");
        lock(8+Math.floorMod(draft.id().hashCode(),8));
        Long actual=db.queryForObject("SELECT COALESCE(MAX(revision),0) FROM enf12_document_draft_revision WHERE draft_id=?",Long.class,draft.id());
        if(actual==null||actual!=expected||draft.revision()!=expected+1)throw new IllegalArgumentException("Draft revision conflict");
        if(!sha(draft.contentJson().getBytes(StandardCharsets.UTF_8)).equals(draft.contentSha256()))throw new IllegalArgumentException("Draft content SHA mismatch");
        String payload=encode(draft);byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);String digest=sha(bytes);
        String key=UUID.randomUUID()+"-"+digest+".json";
        Path file=resolve(key);Path temporary=resolve(key+".tmp");
        try {
            Files.createDirectories(root);Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW);
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file);}
        } catch(Exception failure) {
            try{Files.deleteIfExists(temporary);Files.deleteIfExists(file);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw new IllegalStateException("Draft artifact write failed",failure);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(file);}catch(Exception failure){throw new IllegalStateException("Draft rollback artifact cleanup failed",failure);}
            }
        });
        db.update("INSERT INTO enf12_document_draft_revision(draft_id,revision,organization_id,review_state,content_sha256,input_manifest_sha256,artifact_key,artifact_sha256,payload,actor_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                draft.id(),draft.revision(),draft.manifest().organization(),draft.reviewState(),draft.contentSha256(),draft.inputManifestHash(),key,digest,payload,draft.author(),Timestamp.from(draft.createdAt()));
    }
    @Override public Optional<Candidate> candidate(String id) {
        return db.query("SELECT payload,artifact_key,artifact_sha256 FROM enf12_document_draft_candidate WHERE candidate_id=?",
                (rs,n)->decodeCandidate(rs.getString(1),rs.getString(2),rs.getString(3)),id).stream().findFirst();
    }
    @Override public List<Candidate> candidates(String id) {
        return db.query("SELECT payload,artifact_key,artifact_sha256 FROM enf12_document_draft_candidate WHERE draft_id=? ORDER BY created_at,candidate_id",
                (rs,n)->decodeCandidate(rs.getString(1),rs.getString(2),rs.getString(3)),id);
    }
    @Override public void addCandidate(Candidate candidate) {
        requireTransaction();lock(8+Math.floorMod(candidate.draftId().hashCode(),8));
        assertHead(candidate.draftId(),candidate.baseRevision(),candidate.baseContentSha256());
        if(!sha(candidate.contentJson().getBytes(StandardCharsets.UTF_8)).equals(candidate.contentSha256()))throw new IllegalArgumentException("Candidate content SHA mismatch");
        String payload=encode(candidate);byte[] raw=payload.getBytes(StandardCharsets.UTF_8);String digest=sha(raw);String key=artifact(raw,digest);
        db.update("INSERT INTO enf12_document_draft_candidate(candidate_id,draft_id,base_revision,content_sha256,artifact_key,artifact_sha256,payload,actor_ref,request_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                candidate.id(),candidate.draftId(),candidate.baseRevision(),candidate.contentSha256(),key,digest,payload,candidate.actor(),candidate.requestId(),Timestamp.from(candidate.createdAt()));
    }
    @Override public List<Review> reviews(String id,long revision) {
        return db.query("SELECT payload FROM enf12_document_draft_review WHERE draft_id=? AND revision=? ORDER BY review_sequence",
                (rs,n)->{try{return json.readValue(rs.getString(1),Review.class);}catch(Exception failure){throw new IllegalStateException("Review unreadable",failure);}},id,revision);
    }
    @Override public void addReview(Review review) {
        requireTransaction();lock(8+Math.floorMod(review.draftId().hashCode(),8));assertHead(review.draftId(),review.revision(),review.contentSha256());
        var prior=reviews(review.draftId(),review.revision());String state=prior.isEmpty()?"PENDING_REVIEW":prior.getLast().outcome();
        if("SUBMITTED".equals(review.outcome())?!List.of("PENDING_REVIEW","RETURNED").contains(state):!"SUBMITTED".equals(state))
            throw new IllegalArgumentException("Review transition changed");
        db.update("INSERT INTO enf12_document_draft_review(draft_id,revision,review_sequence,content_sha256,outcome,payload,actor_ref,request_key,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                review.draftId(),review.revision(),prior.size()+1,review.contentSha256(),review.outcome(),encode(review),review.actor(),review.requestId(),Timestamp.from(review.createdAt()));
    }
    private void requireTransaction() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Workflow transaction required");}
    @Override public Optional<ExportArtifact> export(String id) {
        return db.query("SELECT payload FROM enf12_document_draft_export WHERE export_id=?",(rs,n)->{
            try{return json.readValue(rs.getString(1),ExportArtifact.class);}catch(Exception e){throw new IllegalStateException("Export metadata unreadable",e);}
        },id).stream().findFirst();
    }
    @Override public byte[] exportBytes(String id) {
        var rows=db.query("SELECT artifact_key,artifact_sha256 FROM enf12_document_draft_export WHERE export_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},id);
        if(rows.size()!=1)throw new IllegalArgumentException("Export not found");
        try{byte[] bytes=Files.readAllBytes(resolve(rows.getFirst()[0]));
            if(!sha(bytes).equals(rows.getFirst()[1]))throw new IllegalStateException("Export SHA mismatch");return bytes;
        }catch(Exception e){throw new IllegalStateException("Export original missing or corrupt",e);}
    }
    @Override public void addExport(ExportArtifact value,byte[] bytes) {
        requireTransaction();lock(8+Math.floorMod(value.draftId().hashCode(),8));assertHead(value.draftId(),value.revision(),value.contentSha256());
        var draft=read(value.draftId(),value.revision()).orElseThrow();var review=reviews(value.draftId(),value.revision());
        if(!draft.manifest().equals(value.manifest())||draft.missingItems().stream().anyMatch(Diagnostic::blocking)
                ||review.isEmpty()||!"APPROVED".equals(review.getLast().outcome())||!review.getLast().contentSha256().equals(value.contentSha256())
                ||bytes==null||bytes.length==0||!sha(bytes).equals(value.artifactSha256()))
            throw new IllegalArgumentException("Export binding or review mismatch");
        String key=artifact(bytes,value.artifactSha256(),".docx");
        db.update("INSERT INTO enf12_document_draft_export(export_id,draft_id,revision,content_sha256,artifact_key,artifact_sha256,renderer_version,payload,actor_ref,request_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            value.id(),value.draftId(),value.revision(),value.contentSha256(),key,value.artifactSha256(),value.rendererVersion(),encode(value),value.actor(),value.requestId(),Timestamp.from(value.createdAt()));
    }
    private void assertHead(String id,long revision,String digest) {
        Long head=db.queryForObject("SELECT COALESCE(MAX(revision),0) FROM enf12_document_draft_revision WHERE draft_id=?",Long.class,id);
        var exact=read(id,revision).orElseThrow(()->new IllegalArgumentException("Draft revision missing"));
        if(head==null||head!=revision||!exact.contentSha256().equals(digest))throw new IllegalArgumentException("Workflow base changed");
    }
    private String artifact(byte[] bytes,String digest) {
        return artifact(bytes,digest,".json");
    }
    private String artifact(byte[] bytes,String digest,String extension) {
        String key=UUID.randomUUID()+"-"+digest+extension;Path file=resolve(key);Path temporary=resolve(key+".tmp");
        try {Files.createDirectories(root);Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW);
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file);}
        }catch(Exception failure){try{Files.deleteIfExists(temporary);Files.deleteIfExists(file);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw new IllegalStateException("Workflow artifact write failed",failure);}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(file);}catch(Exception failure){throw new IllegalStateException("Workflow artifact cleanup failed",failure);}}
        });return key;
    }
    private Candidate decodeCandidate(String payload,String key,String digest) {
        try {byte[] raw=Files.readAllBytes(resolve(key));
            if(!sha(raw).equals(digest)||!new String(raw,StandardCharsets.UTF_8).equals(payload))throw new IllegalStateException("Candidate DB/artifact mismatch");
            var candidate=json.readValue(payload,Candidate.class);
            if(!sha(candidate.contentJson().getBytes(StandardCharsets.UTF_8)).equals(candidate.contentSha256()))throw new IllegalStateException("Candidate content SHA mismatch");
            return candidate;
        }catch(Exception failure){throw new IllegalStateException("Candidate original unavailable or corrupt",failure);}
    }
    @Override public Receipt command(String key,String fingerprint,String actor,Supplier<Receipt> mutation) {
        if(key==null||key.isBlank()||key.length()>120)throw new IllegalArgumentException("Idempotency key required");
        return tx.execute(status->{
            lock(Math.floorMod(key.hashCode(),8));
            var prior=db.query("SELECT fingerprint,actor_ref,payload FROM enf12_document_draft_receipt WHERE request_key=?",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},key);
            if(!prior.isEmpty()) {
                var p=prior.getFirst();if(!p[0].equals(fingerprint)||!p[1].equals(actor))throw new IllegalArgumentException("Draft receipt conflict");
                return receipt(p[2]);
            }
            var result=mutation.get();
            if(!key.equals(result.requestId()))throw new IllegalStateException("Receipt request mismatch");
            var at=Timestamp.from(clock.instant());
            db.update("INSERT INTO enf12_document_draft_receipt(request_key,fingerprint,actor_ref,draft_id,revision,payload,created_at) VALUES(?,?,?,?,?,?,?)",
                    key,fingerprint,actor,result.draftId(),result.revision(),encode(result),at);
            db.update("INSERT INTO enf12_document_draft_event(event_id,request_key,operation,actor_ref,draft_id,revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),key,result.operation(),actor,result.draftId(),result.revision(),at);
            return result;
        });
    }
    private void lock(int slot) {db.queryForObject("SELECT lock_id FROM enf12_document_draft_lock WHERE lock_id=? FOR UPDATE",Integer.class,slot);}
    private Path resolve(String key) {
        Path file=root.resolve(key).normalize();
        if(!file.startsWith(root)||file.equals(root))throw new SecurityException("Invalid draft artifact path");
        return file;
    }
    private Draft decode(String payload,String key,String digest) {
        try {
            byte[] bytes=Files.readAllBytes(resolve(key));
            if(!sha(bytes).equals(digest)||!new String(bytes,StandardCharsets.UTF_8).equals(payload))throw new IllegalStateException("Draft DB/artifact mismatch");
            Draft draft=json.readValue(payload,Draft.class);
            if(!sha(draft.contentJson().getBytes(StandardCharsets.UTF_8)).equals(draft.contentSha256()))throw new IllegalStateException("Draft content SHA mismatch");
            return draft;
        }catch(Exception failure){throw new IllegalStateException("Draft artifact unavailable or corrupt",failure);}
    }
    private Receipt receipt(String payload) {
        try{return json.readValue(payload,Receipt.class);}catch(Exception failure){throw new IllegalStateException("Draft receipt unreadable",failure);}
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("Draft serialization failed",failure);}
    }
    private static String sha(byte[] bytes) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
