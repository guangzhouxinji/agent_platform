package cn.xinji.agentplatform.enforcement.documentdraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import cn.xinji.agentplatform.enforcement.documentrule.*;
import cn.xinji.agentplatform.enforcement.material.*;
import cn.xinji.agentplatform.enforcement.evidence.*;
import cn.xinji.agentplatform.enforcement.legalreference.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Diagnostic/authorization C only. Mock ports never count as product I or a real case. */
class DocumentDraftInputAuditTest {
    @TempDir Path root;final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final MaterialAccess access=mock(MaterialAccess.class);final JdbcTemplate db=mock(JdbcTemplate.class);
    final RuntimeDocumentDraftInputs runtime=mock(RuntimeDocumentDraftInputs.class);final DocumentTemplateStore templates=mock(DocumentTemplateStore.class);
    final MaterialRepository materials=mock(MaterialRepository.class);final MaterialFiles originals=mock(MaterialFiles.class);
    DocumentDraftInputAudit audit(String org)throws Exception {
        Path caseFile=root.resolve("case.json");Files.writeString(caseFile,json.writeValueAsString(Map.of("caseRef","COMPONENT_PRIVATE_CASE","version",1,"organization",org,"nature","COMPONENT_ONLY_NOT_REAL_CASE","materials",List.of())));
        Path catalog=root.resolve("catalog.json");Files.writeString(catalog,"{\"schemas\":[]}");
        return new DocumentDraftInputAudit(runtime,db,access,json,caseFile,catalog,DocumentProfileCatalog.builtin(json),templates,
            mock(Dm8DocumentRuleStore.class),mock(LegalReferenceStore.class),materials,originals,mock(VerifiedEvidenceSource.class));
    }
    @Test void emptyRuntimeReturnsReasonsAcrossAllApplicableCriteriaNotOnlyEmptyArray()throws Exception {
        var result=audit("component-org").inspect("component-actor","component-org");assertTrue(result.inputs().isEmpty());
        var codes=result.checks().stream().map(DocumentDraftInputAudit.Check::code).toList();
        for(String code:List.of("CASE_SNAPSHOT","NO_CONSUMABLE_EVIDENCE","NO_CURRENT_CASE_REFERENCE","NO_PUBLISHED_FACT_BASELINE","NO_FACT_BASELINE_MAPPING","NO_READABLE_SOURCE_SCHEMA"))assertTrue(codes.contains(code),code);
        assertEquals(2,result.checks().stream().filter(c->c.status().equals("NOT_APPLICABLE")).count());
        verifyNoInteractions(runtime);
    }
    @Test void deniedCaseReadDoesNotDiscloseCoordinatesAndStillExplainsOtherGates()throws Exception {
        doThrow(new SecurityException("denied")).when(access).require(anyString(),anyString(),eq("CASE"),anyString(),anyLong(),eq("READ"),eq("DOCUMENT_DRAFT"));
        var result=audit("component-org").inspect("component-actor","component-org");
        assertTrue(result.inputs().isEmpty());assertTrue(result.checks().stream().noneMatch(c->c.objectId().equals("COMPONENT_PRIVATE_CASE")));
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("CASE_READ_UNAVAILABLE")));
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("EVIDENCE_NOT_INSPECTED")&&c.status().equals("PENDING")));
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("NO_FACT_BASELINE_MAPPING")));
    }
    @Test void differentOrganizationNeverProducesAChoiceOrLeaksTheOtherCase()throws Exception {
        var result=audit("other-org").inspect("component-actor","component-org");assertTrue(result.inputs().isEmpty());
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("CASE_UNAVAILABLE")));
        assertTrue(result.checks().stream().noneMatch(c->c.objectId().equals("COMPONENT_PRIVATE_CASE")));
    }
    @Test void publishedTemplateAloneCannotBecomeAConsumableInput()throws Exception {
        var profile=DocumentProfileCatalog.builtin(json).all().getFirst();when(runtime.profileBinding(profile)).thenReturn("EXACT_COMPONENT_BINDING");
        when(templates.list("component-org")).thenReturn(List.of(new DocumentTemplateContract.Summary(profile.id(),1,DocumentTemplateContract.State.PUBLISHED,profile.title(),"component-org","a".repeat(64))));
        when(templates.revision(profile.id(),1)).thenReturn(Optional.of(new DocumentTemplateContract.Revision(profile.id(),1,DocumentTemplateContract.State.PUBLISHED,profile.title(),profile.documentType().name(),new DocumentTemplateContract.Scope("component-org","*","*"),"EXACT_COMPONENT_BINDING",List.of(),List.of("SimSun"),"component.docx","component.docx","a".repeat(64),"component",Instant.EPOCH,"COMPONENT_PUBLISH")));
        var result=audit("component-org").inspect("component-actor","component-org");assertTrue(result.inputs().isEmpty());
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("TEMPLATE_STATE")&&c.status().equals("PASSED")));
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("NO_FACT_BASELINE_MAPPING")));
    }
    @Test void missingMaterialOriginalIsNotMisreportedAsAMissingCaseSnapshot()throws Exception {
        var reader=audit("component-org");
        Files.writeString(root.resolve("case.json"),json.writeValueAsString(Map.of("caseRef","COMPONENT_PRIVATE_CASE","version",1,"organization","component-org","materials",List.of(Map.of("id","component-material","version",3,"sha256","a".repeat(64))))));
        when(materials.material("component-material")).thenReturn(Optional.of(new MaterialWork.Material("component-material","component-clue","component-org","a".repeat(64),"component-key","COMPONENT_ONLY",Instant.now().plusSeconds(60),List.of("ANALYZE"),"component-actor","COMPONENT_ONLY","VERIFIED",3)));
        when(originals.read("component-key")).thenThrow(new IllegalStateException("Missing original"));
        var result=reader.inspect("component-actor","component-org");assertTrue(result.inputs().isEmpty());
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("MATERIAL_ORIGINAL_UNAVAILABLE")));
        assertFalse(result.checks().stream().anyMatch(c->c.code().equals("CASE_ORIGINAL_UNAVAILABLE")));
        assertTrue(result.checks().stream().anyMatch(c->c.code().equals("CASE_SNAPSHOT")));
    }
}
