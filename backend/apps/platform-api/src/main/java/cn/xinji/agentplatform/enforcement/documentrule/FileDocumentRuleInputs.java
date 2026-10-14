package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateStore;
import cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateFiles;
import cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.State;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Configured versioned schema/source adapter; same product port for project or future center inputs. */
public final class FileDocumentRuleInputs implements Inputs {
    public record Snapshot(String organization,Schema schema,long authorizationRevision,
            String sourceId,long sourceRevision,String sourceFile,String sourceSha256) {}
    public record Catalog(List<Snapshot> schemas) {}
    private final Path catalog;
    private final ObjectMapper json;
    private final DocumentTemplateStore templates;
    private final DocumentTemplateFiles files;
    private final MaterialAccess access;
    private final DocumentRuleDependencyHeads heads;
    private String observedCatalog;
    public FileDocumentRuleInputs(Path catalog,ObjectMapper json,DocumentTemplateStore templates,
            DocumentTemplateFiles files,MaterialAccess access) {
        this(catalog,json,templates,files,access,null);
    }
    public FileDocumentRuleInputs(Path catalog,ObjectMapper json,DocumentTemplateStore templates,
            DocumentTemplateFiles files,MaterialAccess access,DocumentRuleDependencyHeads heads) {
        this.catalog=catalog;this.json=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.templates=templates;this.files=files;this.access=access;
        this.heads=heads;
    }
    @Override public Template template(String actor,String org,String id,long revision) {
        access.require(actor,org,"DOCUMENT_TEMPLATE",id,revision,"READ","DOCUMENT_TEMPLATE");
        var value=templates.revision(id,revision).orElseThrow(()->new IllegalArgumentException("Template unavailable"));
        var current=templates.latest(id).orElseThrow();
        if(!org.equals(value.scope().organization()))throw new SecurityException("Template organization mismatch");
        if(heads!=null)heads.template(org,id,revision);
        if(current.revision()!=revision || current.state()!=State.PUBLISHED)
            throw new DependencyChanged("Template drift or not published");
        if(!hash(files.read(value.fileKey())).equals(value.fileSha256()))throw new IllegalStateException("Template original SHA mismatch");
        return new Template(id,revision,value.fileSha256(),value.fields().stream()
            .map(f->new Field(f.id(),f.type(),f.required(),f.repeat())).toList());
    }
    @Override public Schema schema(String actor,String org,String id,String version) {
        Snapshot snapshot=snapshot(org,id,version);
        access.require(actor,org,"DOCUMENT_SCHEMA",id,snapshot.authorizationRevision(),"READ","DOCUMENT_MAPPING");
        if(heads!=null)heads.schema(org,snapshot.schema(),catalog);
        return snapshot.schema();
    }
    @Override public Map<String,Value> values(String actor,String org,Schema schema,String purpose) {
        Snapshot snapshot=snapshot(org,schema.id(),schema.version());
        access.require(actor,org,"DOCUMENT_MAPPING_SOURCE",snapshot.sourceId(),snapshot.sourceRevision(),"READ",purpose);
        byte[] raw=read(resolve(snapshot.sourceFile()));
        if(!hash(raw).equals(snapshot.sourceSha256()))throw new IllegalStateException("Mapping source SHA mismatch");
        try {
            Map<String,Value> values=json.readValue(raw,new TypeReference<Map<String,Value>>(){});
            if(values.values().stream().anyMatch(v->!snapshot.sourceId().equals(v.sourceRef())
                    ||!Long.toString(snapshot.sourceRevision()).equals(v.sourceVersion())||v.locator()==null||v.locator().isBlank()))
                throw new IllegalArgumentException("Exact mapping source provenance required");
            access.require(actor,org,"DOCUMENT_MAPPING_SOURCE",snapshot.sourceId(),snapshot.sourceRevision(),"READ",purpose);
            return Map.copyOf(values);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("Mapping source unreadable",e);}
    }
    @Override public void assertCurrent(String actor,String org,Rule rule,String purpose) {
        template(actor,org,rule.templateId(),rule.templateRevision());
        schema(actor,org,rule.schemaId(),rule.schemaVersion());
        Snapshot snapshot=snapshot(org,rule.schemaId(),rule.schemaVersion());
        access.require(actor,org,"DOCUMENT_MAPPING_SOURCE",snapshot.sourceId(),snapshot.sourceRevision(),"READ",purpose);
        if(!hash(read(resolve(snapshot.sourceFile()))).equals(snapshot.sourceSha256()))throw new IllegalStateException("Mapping source SHA mismatch");
    }
    @Override public void assertAccess(String actor,String org,Rule rule,String purpose) {
        access.require(actor,org,"DOCUMENT_TEMPLATE",rule.templateId(),rule.templateRevision(),"READ","DOCUMENT_TEMPLATE");
        Snapshot snapshot=snapshot(org,rule.schemaId(),rule.schemaVersion());
        access.require(actor,org,"DOCUMENT_SCHEMA",rule.schemaId(),snapshot.authorizationRevision(),"READ",purpose);
        access.require(actor,org,"DOCUMENT_MAPPING_SOURCE",snapshot.sourceId(),snapshot.sourceRevision(),"READ",purpose);
    }
    @Override public void assertValueAccess(String actor,String org,Mapped value,String purpose) {
        access.require(actor,org,"DOCUMENT_MAPPING_SOURCE",value.sourceRef(),Long.parseLong(value.sourceVersion()),"READ",purpose);
    }
    private Snapshot snapshot(String org,String id,String version) {
        byte[] raw=read(catalog);String digest=hash(raw);
        if(observedCatalog!=null&&!observedCatalog.equals(digest))throw new IllegalStateException("Schema catalog changed during operation");
        observedCatalog=digest;
        try {
            var value=json.readValue(raw,Catalog.class);
            var matching=value.schemas().stream().filter(s->org.equals(s.organization())&&id.equals(s.schema().id())&&version.equals(s.schema().version())).toList();
            if(matching.size()!=1)throw new IllegalArgumentException("Exact unique schema snapshot required");
            var s=matching.getFirst();
            if(s.authorizationRevision()<1||s.sourceRevision()<1||s.sourceSha256()==null||!s.sourceSha256().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid source version or SHA");
            return s;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("Schema catalog unavailable",e);}
    }
    private Path resolve(String ref) { Path p=Path.of(ref);return p.isAbsolute()?p:catalog.toAbsolutePath().getParent().resolve(p).normalize(); }
    private static byte[] read(Path file) {try{return Files.readAllBytes(file);}catch(Exception e){throw new IllegalStateException("Mapping input original unavailable",e);} }
    private static String hash(byte[] value) {try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);} }
}
