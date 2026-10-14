package cn.xinji.agentplatform.enforcement.documentdraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import java.time.Clock;

/** C parser/lifecycle compatibility only, NOT runtime publishing, render QA or browser I. */
class DocumentProfileTemplateAssetTest {
    final List<DocumentTemplateProfile> profiles=DocumentProfileCatalog.builtin(new ObjectMapper().findAndRegisterModules()).all();
    @Test void threeConfiguredAssetsUseTheFrozenTemplateCreateAndPublishContract(){
        var store=new Memory();var service=new DocumentTemplateService(store,store,(a,o,id,r,action)->{},Clock.systemUTC(),Set.of("SimSun"));
        for(var p:profiles){
            var metadata=new DraftInput(p.id(),p.title(),p.documentType().name(),new Scope("component-org","*",p.documentType().name()),
                    "PROJECT_ENGINEERING_BASELINE_NOT_BUSINESS_APPROVAL",p.fields().stream().map(f->new Field(f.id(),f.type(),f.requiredForReview(),f.repeated(),null)).toList(),List.of("SimSun"));
            byte[] bytes=new DocumentProfileTemplateAsset().create(p);
            var created=service.create("component-actor",p.id()+"-create",metadata,p.id()+".docx",bytes);
            assertEquals(State.DRAFT,created.state());
            var published=service.publish("component-actor",p.id()+"-publish",p.id(),created.revision(),new TransitionInput(created.revision(),"EXPLICIT_COMPONENT_EVIDENCE"));
            assertEquals(State.PUBLISHED,published.state());assertEquals(created.fileSha256(),published.fileSha256());
        }
        assertEquals(3,store.records.size());
    }
    @Test void assetBytesAreDeterministicAndDoNotContainCaseValues(){
        for(var p:profiles){byte[] first=new DocumentProfileTemplateAsset().create(p);assertArrayEquals(first,new DocumentProfileTemplateAsset().create(p));
            assertTrue(first.length>0);assertEquals("NON_OFFICIAL",p.classification());}
    }
    @Test void metadataWithUndeclaredFieldsCannotImportAnAsset(){
        var p=profiles.getFirst();var store=new Memory();var service=new DocumentTemplateService(store,store,(a,o,id,r,action)->{},Clock.systemUTC(),Set.of("SimSun"));
        var metadata=new DraftInput(p.id(),p.title(),"FACT_RECORD",new Scope("component-org","*","FACT_RECORD"),"COMPONENT",
                List.of(new Field("undeclared","STRING",true,false,null)),List.of("SimSun"));
        assertThrows(IllegalArgumentException.class,()->service.create("actor","bad",metadata,"bad.docx",new DocumentProfileTemplateAsset().create(p)));
        assertTrue(store.records.isEmpty());assertTrue(store.files.isEmpty());
    }
    static final class Memory implements DocumentTemplateStore,DocumentTemplateFiles {
        final Map<String,Revision> records=new HashMap<>();final Map<String,byte[]> files=new HashMap<>();
        public Optional<Revision> latest(String id){return Optional.ofNullable(records.get(id));}
        public Optional<Revision> revision(String id,long revision){return latest(id).filter(r->r.revision()==revision);}
        public List<Summary> list(String org){return List.of();}
        public void add(Revision r){records.put(r.templateId(),r);}
        public void transition(String id,long revision,State expected,State state,String evidence){var r=records.get(id);if(r.state()!=expected)throw new IllegalArgumentException("CAS");records.put(id,new Revision(id,r.revision(),state,r.name(),r.stage(),r.scope(),r.approvalRef(),r.fields(),r.fontNames(),r.fileKey(),r.fileName(),r.fileSha256(),r.createdBy(),r.createdAt(),evidence));}
        public void addPreview(PreviewArtifact p){throw new UnsupportedOperationException();}
        public Optional<PreviewArtifact> preview(String id,String preview){return Optional.empty();}
        public <T>T command(String key,String fingerprint,String actor,String operation,Supplier<T> body,Class<T> type){return body.get();}
        public String put(String kind,String sha,byte[] bytes){String key=kind+"/"+sha;files.put(key,bytes.clone());return key;}
        public byte[] read(String key){return files.get(key).clone();}
        public void delete(String key){files.remove(key);}
    }
}
