package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

/** OOXML component validation only; controlled values and mock template ports are never product I. */
class DocumentDraftDocxRendererTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final DocumentTemplateStore templates=mock(DocumentTemplateStore.class);final DocumentTemplateFiles files=mock(DocumentTemplateFiles.class);
    final DocumentDraftDocxRenderer renderer=new DocumentDraftDocxRenderer(templates,files,json);
    DocumentProfileEngine.Content content(DocumentTemplateProfile profile) {
        var rule=new DocumentRuleContract.Rule("COMPONENT_RULE",1,"component",DocumentRuleContract.State.PUBLISHED,profile.id(),1,
            sha(new DocumentProfileTemplateAsset().create(profile)),"COMPONENT_SCHEMA","1",profile.defaultMappings());
        var sources=new HashMap<String,DocumentProfileEngine.SourceDatum>();var mapped=new ArrayList<DocumentRuleContract.Mapped>();
        for(var field:profile.fields()) {
            var m=rule.mappings().stream().filter(v->v.fieldId().equals(field.id())).findFirst().orElseThrow();
            Object scalar=switch(field.type()){case "DATE"->"2026-10-02";case "DECIMAL"->new java.math.BigDecimal("0.00");default->"component <text> & not business fact";};
            Object value=field.repeated()?List.of(scalar,scalar):scalar;
            sources.put(m.sourcePath(),new DocumentProfileEngine.SourceDatum(value,"COMPONENT_SOURCE",1,"a".repeat(64),"component locator",true));
            mapped.add(new DocumentRuleContract.Mapped(field.id(),value,"COMPONENT_SOURCE","1","component locator",m.mode(),false));
        }
        return new DocumentProfileEngine().compose(profile,Instant.parse("2026-10-02T04:00:00Z"),null,null,rule,sources,new DocumentRuleContract.Trial(true,false,mapped,List.of()));
    }
    Draft draft(DocumentProfileEngine.Content content) throws Exception {
        var p=content.profile();byte[] template=new DocumentProfileTemplateAsset().create(p);String templateSha=sha(template);
        var revision=new DocumentTemplateContract.Revision(p.id(),1,DocumentTemplateContract.State.PUBLISHED,p.title(),p.documentType().name(),
            new DocumentTemplateContract.Scope("component","*","*"),"COMPONENT_BASELINE",p.fields().stream().map(f->new DocumentTemplateContract.Field(f.id(),f.type(),f.requiredForReview(),f.repeated(),null)).toList(),List.of("SimSun"),"template",p.id()+".docx",templateSha,"component",Instant.EPOCH,"COMPONENT_PUBLISH");
        when(templates.revision(p.id(),1)).thenReturn(Optional.of(revision));when(files.read("template")).thenReturn(template);
        var v=new ObjectVersion("COMPONENT_SOURCE",1,"a".repeat(64));var input=new Input("component",p.documentType(),v,new ObjectVersion(p.id(),1,templateSha),new ObjectVersion("COMPONENT_RULE",1,"b".repeat(64)),List.of(v),List.of(v),null,null);
        String body=json.writeValueAsString(content);return new Draft("component-draft",1,input,body,sha(body.getBytes(StandardCharsets.UTF_8)),"c".repeat(64),"PENDING_REVIEW",List.of(),"component",true,"component-request",Instant.EPOCH);
    }
    @Test void allThreeTypesRenderExactSnapshotsEscapingAndRepeatRowsDeterministically() throws Exception {
        for(var profile:DocumentProfileCatalog.builtin(json).all()) {
            var draft=draft(content(profile));byte[] first=renderer.render("component",draft);assertArrayEquals(first,renderer.render("component",draft));
            String xml=body(first);assertFalse(xml.contains("{{"));assertTrue(xml.contains("NON_OFFICIAL"));assertTrue(xml.contains("&lt;text&gt; &amp;"));
            assertTrue(xml.contains("2026-10-02"));assertTrue(first.length>0);
        }
    }
    @Test void missingCorruptTemplateAndWrongContentShaNeverRenderSuccess() throws Exception {
        var original=draft(content(DocumentProfileCatalog.builtin(json).all().getFirst()));
        var tampered=new Draft(original.id(),original.revision(),original.manifest(),original.contentJson(),"0".repeat(64),original.inputManifestHash(),original.reviewState(),original.missingItems(),original.author(),original.systemAssisted(),original.requestId(),original.createdAt());
        assertThrows(IllegalArgumentException.class,()->renderer.render("component",tampered));
        when(files.read("template")).thenReturn(new byte[]{0});assertThrows(IllegalStateException.class,()->renderer.render("component",original));
        when(files.read("template")).thenThrow(new IllegalStateException("Missing original"));assertThrows(IllegalStateException.class,()->renderer.render("component",original));
    }
    @Test void narrativeTemplateSyntaxIsDataAndMultilineTextUsesWordBreaks() throws Exception {
        var base=content(DocumentProfileCatalog.builtin(json).all().getFirst());var sections=base.sections().stream().map(s->
            new DocumentProfileEngine.SectionValue(s.id(),s.title(),s.visibility(),s.conditionSource(),s.fields().stream().map(f->
                !f.definition().repeated()&&"LONG_TEXT".equals(f.definition().type())?
                    new DocumentProfileEngine.FieldValue(f.definition(),f.source(),"literal {{case_ref}}\nsecond line <tag> &",false,f.needsReview()):f).toList())).toList();
        var document=draft(new DocumentProfileEngine.Content(base.schemaVersion(),base.profile(),base.rule(),false,false,sections,List.of()));
        String xml=body(renderer.render("component",document));assertTrue(xml.contains("literal {{case_ref}}"));assertTrue(xml.contains("w:br"));assertTrue(xml.contains("second line &lt;tag&gt; &amp;"));
    }
    @Test void hiddenSectionRemovedAndUnknownConditionRefusesExport() throws Exception {
        var base=content(DocumentProfileCatalog.builtin(json).all().getFirst());var sections=new ArrayList<>(base.sections());var last=sections.getLast();
        sections.set(sections.size()-1,new DocumentProfileEngine.SectionValue(last.id(),last.title(),DocumentProfileEngine.Visibility.HIDDEN,null,List.of()));
        var hidden=new DocumentProfileEngine.Content(base.schemaVersion(),base.profile(),base.rule(),false,false,sections,List.of());
        assertFalse(body(renderer.render("component",draft(hidden))).contains(last.title()));
        sections.set(sections.size()-1,new DocumentProfileEngine.SectionValue(last.id(),last.title(),DocumentProfileEngine.Visibility.UNKNOWN,null,last.fields()));
        var unknown=draft(new DocumentProfileEngine.Content(base.schemaVersion(),base.profile(),base.rule(),false,true,sections,List.of()));
        assertThrows(IllegalArgumentException.class,()->renderer.render("component",unknown));
    }
    static String body(byte[] bytes)throws IOException{try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().equals("word/document.xml"))return new String(zip.readAllBytes(),StandardCharsets.UTF_8);}throw new IOException("Missing DOCX body");}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
