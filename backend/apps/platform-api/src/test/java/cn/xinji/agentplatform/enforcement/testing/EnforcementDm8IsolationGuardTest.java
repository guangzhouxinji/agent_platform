package cn.xinji.agentplatform.enforcement.testing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Pure guard contract component tests. No database/product/real human claim. */
class EnforcementDm8IsolationGuardTest {
    static final String RUN="ENF14-TEST-ISOLATION-20261004-88",SCHEMA="ENFISO_ABCDEF123456",URL="jdbc:dm://127.0.0.1:5381",VOLUME="owned-data";
    final ObjectMapper json=new ObjectMapper();
    ObjectNode container() {
        var c=json.createObjectNode();c.put("id","abcdef1234567890");c.put("running",true);
        c.putObject("labels").put("agent-platform.role","enforcement-test").put("agent-platform.run-id",RUN);
        c.putObject("ports").putArray("5236/tcp").addObject().put("HostIp","127.0.0.1").put("HostPort","5381");
        c.putArray("mounts").addObject().put("Type","volume").put("Destination","/opt/dmdbms/data").put("Name",VOLUME).put("RW",true);return c;
    }
    ObjectNode volume(){var v=json.createObjectNode();v.put("name",VOLUME);v.putObject("labels").put("agent-platform.role","enforcement-test").put("agent-platform.run-id",RUN);return v;}
    void rejects(ObjectNode c,ObjectNode v){assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateDocker(URL,RUN,VOLUME,c,v));}
    @Test void dedicatedEndpointContractAcceptsOnlyMatchingRandomSchema(){assertDoesNotThrow(()->EnforcementDm8IsolationGuard.validateEndpoint(URL,SCHEMA,SCHEMA,RUN));}
    @Test void everyKnownProductAndRecoveryPortIsRejected(){for(int port:new int[]{5236,5237,5301,5302,5311,5312,5319,5367,5370})assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateEndpoint("jdbc:dm://127.0.0.1:"+port,SCHEMA,SCHEMA,RUN));}
    @Test void runtimeSchemaRejectedEvenOnIndependentPort(){for(String user:new String[]{"ENFACC_BIZ","SYSDBA","ENFISO_ABCDEF123456_OTHER"})assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateEndpoint(URL,user,user,RUN));}
    @Test void userAndSchemaMismatchRejected(){assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateEndpoint(URL,"ENFISO_AAAAAAAAAAAA",SCHEMA,RUN));}
    @Test void remoteOrParameterizedEndpointRejected(){for(String url:new String[]{"jdbc:dm://localhost:5381","jdbc:dm://192.0.2.1:5381",URL+"?schema=ENFACC_BIZ","jdbc:h2:mem:test"})assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateEndpoint(url,SCHEMA,SCHEMA,RUN));}
    @Test void missingRunOrInvalidPortRejected(){assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateEndpoint(URL,SCHEMA,SCHEMA,null));assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateEndpoint("jdbc:dm://127.0.0.1:99999",SCHEMA,SCHEMA,RUN));}
    @Test void matchedOwnedDockerFactsAcceptedAsComponentContractOnly(){assertDoesNotThrow(()->EnforcementDm8IsolationGuard.validateDocker(URL,RUN,VOLUME,container(),volume()));}
    @Test void stoppedContainerRejected(){var c=container();c.put("running",false);rejects(c,volume());}
    @Test void wrongContainerRoleRejected(){var c=container();((ObjectNode)c.get("labels")).put("agent-platform.role","product");rejects(c,volume());}
    @Test void otherRunContainerRejected(){var c=container();((ObjectNode)c.get("labels")).put("agent-platform.run-id","OLD-RUN");rejects(c,volume());}
    @Test void wrongVolumeOwnerRejected(){var v=volume();((ObjectNode)v.get("labels")).put("agent-platform.run-id","OLD-RUN");rejects(container(),v);}
    @Test void missingVolumeEvidenceRejected(){rejects(container(),json.createObjectNode());}
    @Test void wrongPortOrPublicBindingRejected(){for(String key:new String[]{"HostPort","HostIp"}){var c=container();((ObjectNode)c.get("ports").get("5236/tcp").get(0)).put(key,key.equals("HostPort")?"5311":"0.0.0.0");rejects(c,volume());}}
    @Test void otherVolumeMountRejected(){var c=container();((ObjectNode)c.get("mounts").get(0)).put("Name","product-data");rejects(c,volume());}
    @Test void sharedHostBindMountRejected(){var c=container();((ObjectNode)c.get("mounts").get(0)).put("Type","bind");rejects(c,volume());}
    @Test void missingDataMountRejected(){var c=container();c.putArray("mounts");rejects(c,volume());}
    @Test void additionalUnknownVolumeRejected(){var c=container();((com.fasterxml.jackson.databind.node.ArrayNode)c.get("mounts")).addObject().put("Type","volume").put("Name","product-volume").put("Destination","/extra");rejects(c,volume());}
    java.nio.file.Path ownedRoot(String run) throws Exception {var p=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"enf-owned-"+run,"files");java.nio.file.Files.createDirectories(p);return p;}
    String uniqueRun(){return "ENF-GUARD-C-"+java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);}
    void marker(java.nio.file.Path p,String run,String schema) throws Exception {var m=json.createObjectNode().put("runId",run).put("schema",schema).put("volume",VOLUME).put("containerId","container-c-only");java.nio.file.Files.writeString(p.resolve(".enf-test-ownership.json"),m.toString());}
    @Test void exactFileOwnershipAcceptedAsComponentOnly() throws Exception {String run=uniqueRun();var p=ownedRoot(run);marker(p,run,SCHEMA);assertDoesNotThrow(()->EnforcementDm8IsolationGuard.validateFiles(run,SCHEMA,VOLUME,"container-c-only",p));}
    @Test void missingFileOwnershipRejected() throws Exception {String run=uniqueRun();var p=ownedRoot(run);assertThrows(java.nio.file.NoSuchFileException.class,()->EnforcementDm8IsolationGuard.validateFiles(run,SCHEMA,VOLUME,"container-c-only",p));}
    @Test void foreignFileOwnershipRejected() throws Exception {String run=uniqueRun();var p=ownedRoot(run);marker(p,"OTHER-RUN",SCHEMA);assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateFiles(run,SCHEMA,VOLUME,"container-c-only",p));}
    @Test void runtimeFileRootRejectedBeforeReadingMarker(){assertThrows(IllegalStateException.class,()->EnforcementDm8IsolationGuard.validateFiles(RUN,SCHEMA,VOLUME,"container-c-only",java.nio.file.Path.of("var/enforcement-document-draft")));}
    @Test void repeatedRunRejectedWithoutRemovingOwnedEvidence() throws Exception {String run=uniqueRun();var p=ownedRoot(run);marker(p,run,SCHEMA);EnforcementDm8IsolationGuard.beginRun(p,run);assertThrows(java.nio.file.FileAlreadyExistsException.class,()->EnforcementDm8IsolationGuard.beginRun(p,run));assertEquals(run,java.nio.file.Files.readString(p.resolve(".enf-rbc-run-started")));assertTrue(java.nio.file.Files.isRegularFile(p.resolve(".enf-test-ownership.json")));}
}
