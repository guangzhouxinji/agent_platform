package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact reviewed revision export; it never revises content or grants official status. */
public final class DocumentDraftExportService {
    private final DocumentDraftService drafts;private final Inputs inputs;private final Access access;
    private final Store store;private final ExportRenderer renderer;private final Clock clock;
    public DocumentDraftExportService(DocumentDraftService drafts,Inputs inputs,Access access,Store store,ExportRenderer renderer,Clock clock) {
        this.drafts=drafts;this.inputs=inputs;this.access=access;this.store=store;this.renderer=renderer;this.clock=clock;
    }
    public Receipt create(String actor,String key,String id,long revision,String expectedSha) {
        var draft=drafts.read(actor,id,revision);require(actor,draft);
        if(!draft.contentSha256().equals(expectedSha))throw new IllegalArgumentException("Export content SHA mismatch");
        inputs.assertCurrent(actor,draft.manifest());approved(draft);
        String fingerprint=sha(("EXPORT|"+id+"|"+revision+"|"+expectedSha+"|"+renderer.version()).getBytes(StandardCharsets.UTF_8));
        var receipt=store.command(key,fingerprint,actor,()->{
            require(actor,draft);head(draft);inputs.assertCurrent(actor,draft.manifest());approved(draft);
            byte[] bytes=renderer.render(actor,draft);
            if(bytes==null||bytes.length==0)throw new IllegalStateException("Empty export refused");
            String exportId=UUID.randomUUID().toString();
            store.addExport(new ExportArtifact(exportId,id,revision,draft.manifest(),expectedSha,renderer.version(),sha(bytes),actor,key,clock.instant()),bytes);
            require(actor,draft);inputs.assertCurrent(actor,draft.manifest());approved(draft);
            return new Receipt(key,"EXPORT",id,revision,expectedSha,null,exportId);
        });
        require(actor,draft);inputs.assertCurrent(actor,draft.manifest());approved(draft);
        // Replay cannot report success if the retained original was lost or corrupted.
        read(actor,receipt.exportId());bytes(actor,receipt.exportId());return receipt;
    }
    public ExportArtifact read(String actor,String exportId) {
        var artifact=store.export(exportId).orElseThrow(()->new IllegalArgumentException("Export not found"));
        var draft=drafts.read(actor,artifact.draftId(),artifact.revision());require(actor,draft);
        if(!artifact.contentSha256().equals(draft.contentSha256())||!artifact.manifest().equals(draft.manifest()))
            throw new IllegalStateException("Export revision binding mismatch");
        inputs.assertReadable(actor,artifact.manifest());require(actor,draft);return artifact;
    }
    public byte[] bytes(String actor,String exportId) {
        var artifact=read(actor,exportId);byte[] bytes=store.exportBytes(exportId);
        if(!sha(bytes).equals(artifact.artifactSha256()))throw new IllegalStateException("Export original SHA mismatch");
        read(actor,exportId);return bytes;
    }
    private void approved(Draft draft) {
        var reviews=store.reviews(draft.id(),draft.revision());
        if(draft.missingItems().stream().anyMatch(Diagnostic::blocking)||reviews.isEmpty()
                ||!"APPROVED".equals(reviews.getLast().outcome())||!draft.contentSha256().equals(reviews.getLast().contentSha256()))
            throw new IllegalArgumentException("Exact complete reviewed revision required for export");
    }
    private void head(Draft draft) {
        var history=store.history(draft.id());
        if(history.isEmpty()||history.getLast().revision()!=draft.revision()||!history.getLast().contentSha256().equals(draft.contentSha256()))
            throw new IllegalArgumentException("Stale export revision refused");
    }
    private void require(String actor,Draft draft){access.require(actor,draft.manifest().organization(),draft.id(),draft.revision(),"EXPORT");}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
