package cn.xinji.agentplatform.enforcement.documentdraft;

import cn.xinji.agentplatform.enforcement.agent.EnforcementAgent;
import cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.procedure.*;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentRunPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adapter binding regression only: engineering input, no legal/procedure acceptance. */
class ProductEnforcementAgentCaseBindingTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Input document = new Input("org",Type.FACT_RECORD,ref("case",2),ref("template",1),ref("rule",1),List.of(ref("evidence",1)),List.of(),null,null);
    ObjectVersion ref(String id,long version) { return new ObjectVersion(id,version,"a".repeat(64)); }
    EnforcementProcedureController.CheckCommand procedure(String id,String version) {
        return new EnforcementProcedureController.CheckCommand("check",id,version,"procedure-rule",1,"calendar","1");
    }
    ProductEnforcementAgentTools tools() {
        return new ProductEnforcementAgentTools(mock(EnforcementDocumentDraftController.Context.class),mock(ProcedureService.class),"actor",json);
    }
    Checkpoint checkpoint(String description) {
        return new Checkpoint("run",EnforcementAgent.DEFINITION,new Task("draft",EnforcementAgent.TASK_TYPE,"org",new Goal("case","manifest",description,EnforcementAgent.COMPLETION_CONTRACT)),new Budget(10,1,Duration.ofMinutes(3)),Instant.parse("2026-10-06T00:00:00Z"),0,Phase.OBSERVE,null,0,0,0,List.of(),null,null);
    }
    String retainedInput(String caseId,String version) throws Exception {
        var value=json.createObjectNode();value.set("document",json.valueToTree(document));value.set("procedure",json.valueToTree(procedure(caseId,version)));
        return json.writeValueAsString(value);
    }
    @Test void constructorRejectsSameCaseWithDifferentVersion() {
        assertThrows(IllegalArgumentException.class,()->new ProductEnforcementAgentTools.Input(document,procedure("case","1")));
    }
    @Test void constructorRejectsForeignCaseAndMissingInputs() {
        assertThrows(IllegalArgumentException.class,()->new ProductEnforcementAgentTools.Input(document,procedure("foreign","2")));
        assertThrows(IllegalArgumentException.class,()->new ProductEnforcementAgentTools.Input(null,procedure("case","2")));
        assertThrows(IllegalArgumentException.class,()->new ProductEnforcementAgentTools.Input(document,null));
    }
    @Test void restoredCheckpointCannotBypassExactVersionBinding() throws Exception {
        assertThrows(IllegalArgumentException.class,()->tools().input(checkpoint(retainedInput("case","1"))));
    }
    @Test void restoredCheckpointRequiresCanonicalCaseVersion() throws Exception {
        for(String version:Arrays.asList(null,"","02","2.0","3"))
            assertThrows(IllegalArgumentException.class,()->tools().input(checkpoint(retainedInput("case",version))));
    }
    @Test void exactCaseVersionSurvivesSerializationForCurrentChecks() throws Exception {
        var input=new ProductEnforcementAgentTools.Input(document,procedure("case","2"));
        assertEquals(input,tools().input(checkpoint(json.writeValueAsString(input))));
    }
    @Test void controllerRejectsMissingDocumentBeforeAccessingServices() {
        var documents=mock(EnforcementDocumentDraftController.class);
        var procedures=mock(EnforcementProcedureController.class);
        var materials=mock(MaterialRepository.class);
        var persistence=mock(AgentRunPersistence.class);
        var controller=new ProductEnforcementAgentController(documents,procedures,materials,persistence,json,mock(cn.xinji.agentplatform.runtime.AuthorizedAgentExecution.class));
        assertThrows(IllegalArgumentException.class,()->controller.start("draft",null,"key",null));
        assertThrows(IllegalArgumentException.class,()->controller.start("draft",null,"key",new ProductEnforcementAgentTools.Input(null,procedure("case","2"))));
        verifyNoInteractions(documents,procedures,materials,persistence);
    }
}
