package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Publishes exact existing fact-template mapping using product commands, preserving actual missing values. */
class EnforcementDocumentDraftPublicMappingIT {
    @Test void publishExactFactBaselineRuleAndRetainPublicMissingValues()throws Exception {
        var s=new EnforcementDocumentDraftPublicAdmissionIT();s.initialize();
        var root=Path.of(EnforcementDocumentDraftPublicAdmissionIT.required("ENF_DRAFT_LINKED_CASE_ROOT"));
        var context=s.json.readTree(Files.readAllBytes(root.resolve("case.json")));
        var catalog=s.json.readTree(Files.readAllBytes(root.resolve("mapping-catalog.json")));
        var schema=catalog.path("schemas").get(catalog.path("schemas").size()-1);
        s.grant(s.actor,"CASE",context.path("caseRef").asText(),1,"READ","DOCUMENT_DRAFT");
        for(var ref:context.path("materials"))s.grant(s.actor,"MATERIAL",ref.path("id").asText(),ref.path("version").asLong(),"READ","DOCUMENT_DRAFT");
        s.grant(s.actor,"DOCUMENT_TEMPLATE","ENF14-BUILTIN-FACT_RECORD",1,"READ","DOCUMENT_TEMPLATE");
        s.grant(s.actor,"DOCUMENT_SCHEMA",schema.at("/schema/id").asText(),schema.path("authorizationRevision").asLong(),"READ","DOCUMENT_MAPPING");
        s.grant(s.actor,"DOCUMENT_MAPPING_SOURCE",schema.path("sourceId").asText(),schema.path("sourceRevision").asLong(),"READ","DOCUMENT_MAPPING");
        s.grant(s.actor,"DOCUMENT_TEMPLATE","*",1,"READ","DOCUMENT_TEMPLATE");
        var profile=s.get(s.token,"/document-drafts/baseline-profiles?organization="+s.org);
        var fact=java.util.stream.StreamSupport.stream(profile.spliterator(),false).filter(p->p.path("documentType").asText().equals("FACT_RECORD")).findFirst().orElseThrow();
        // Template SHA/version come from the real published runtime object, not a D input bundle.
        var template=s.db.queryForMap("SELECT state,file_sha256,payload FROM enf9_document_template_revision WHERE template_id=? AND revision=1","ENF14-BUILTIN-FACT_RECORD");
        assertEquals("PUBLISHED",template.get("STATE"));
        String id="ENF14-FACT-MAPPING-"+s.run;
        for(String action:List.of("EDIT","READ","VALIDATE","TRIAL","PUBLISH"))s.grant(s.actor,"DOCUMENT_RULE",id,1,action,"DOCUMENT_MAPPING");
        var input=s.json.createObjectNode();input.put("id",id).put("revision",1).put("organization",s.org).put("state","DRAFT");
        input.put("templateId","ENF14-BUILTIN-FACT_RECORD").put("templateRevision",1).put("templateSha256",template.get("FILE_SHA256").toString());
        input.put("schemaId",schema.at("/schema/id").asText()).put("schemaVersion",schema.at("/schema/version").asText());
        input.set("mappings",fact.path("defaultMappings"));assertEquals(14,input.path("mappings").size());
        var save=s.command(s.token,"/document-rules",Map.of("expectedRevision",0,"rule",input));
        var checked=s.command(s.token,"/document-rules/"+id+"/validate",Map.of("expectedRevision",1,"purpose","DOCUMENT_MAPPING"));assertTrue(checked.path("valid").asBoolean());
        var trial=s.command(s.token,"/document-rules/"+id+"/trial",Map.of("expectedRevision",1,"purpose","DOCUMENT_MAPPING"));
        assertFalse(trial.path("complete").asBoolean());assertTrue(trial.path("diagnostics").size()>0,"Unknown source facts remain gaps");
        var published=s.command(s.token,"/document-rules/"+id+"/publish",Map.of("expectedRevision",1,"purpose","DOCUMENT_MAPPING"));
        s.grant(s.actor,"DOCUMENT_RULE",id,2,"READ","DOCUMENT_MAPPING");
        var saved=s.get(s.token,"/document-rules/"+id+"?revision=2");assertEquals("PUBLISHED",saved.path("state").asText());assertEquals(2,saved.path("revision").asLong());
        assertEquals("ENF14-BUILTIN-FACT_RECORD",saved.path("templateId").asText());assertEquals(1,saved.path("templateRevision").asLong());assertEquals(input.path("mappings"),saved.path("mappings"));
        var attempts=s.get(s.token,"/document-rules/"+id+"/attempts");assertEquals(2,attempts.size());
        s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("fact-rule-publication.json").toFile(),Map.of("run",s.run,"nature","FUNCTIONAL_VALIDATION_NOT_BUSINESS_ACCEPTANCE","save",save,"validation",checked,"trial",trial,"publication",published,"rule",saved,"attempts",attempts,"case",context,"commands",s.commands));
    }
}
