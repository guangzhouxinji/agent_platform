package cn.xinji.agentplatform.enforcement.deployment.entry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.io.*;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
class ProductEnforcementEntryPolicyTest {
    @Test void explicitAdmissionAndDependencyClosureRequired(){
        for(var pages:List.of(List.<String>of(),List.of("material"),List.of("job-pack","job-pack"),List.of("job-pack","document-rules")))
            assertThrows(IllegalArgumentException.class,()->new ProductEnforcementEntryPolicy(pages,List.of()));
        assertThrows(IllegalArgumentException.class,()->new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of("assets/../secret")));
    }
    @Test void originalPermissionsAreStillRequiredAfterAdmittedNamespace(){
        var p=new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of("assets/entry.js"));
        for(String path:List.of("/","/app/enforcement","/app/enforcement.html","/app/enforcement/job-pack","/app/assets/entry.js","/v2/identity/me","/oauth2/authorize","/v2/enforcement/job-packs/collection-tasks"))assertTrue(p.allows("GET",path),path);
        assertTrue(p.allows("POST","/v2/enforcement/job-packs/t/facts/TASK"));
        assertFalse(p.allows("POST","/app/enforcement"));
    }
    @Test void disabledLegacyCrossDomainAndMalformedRoutesReject(){
        var p=new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of("assets/entry.js"));
        for(String path:List.of("/api/v0/enforcement/discovery-rules","/app/index.html","/app/consultation","/app/adjudication","/app/enforcement/document-templates","/v2/enforcement/document-templates","/v2/enforcement/document-drafts/d/agent-runs","/v2/agent-executions","/v2/enforcement/job-packs-other","/app/assets/unowned.js","/v2/enforcement/job-packs/../document-drafts","/v2/enforcement/job-packs%252f../document-drafts","/v2/enforcement/job-packs;x","/v2//enforcement/job-packs","/bad%zz"))assertFalse(p.allows("GET",path),path);
    }
    @Test void onlyExplicitManagementPagesAreAdmitted(){
        var p=new ProductEnforcementEntryPolicy(List.of("job-pack","document-templates","document-rules","legal-references"),List.of());
        for(String path:List.of("/v2/enforcement/document-templates/t","/v2/enforcement/document-rules/r","/v2/enforcement/legal-basis/searches","/v2/enforcement/legal-references/l"))assertTrue(p.allows("POST",path),path);
        assertFalse(p.allows("GET","/v2/enforcement/penalty/rules"));
    }
    @Test void absentBundleFailsStartup(){assertThrows(IllegalStateException.class,()->ProductEnforcementEntryConfiguration.load(new ObjectMapper(),p->null));}
    @Test void exactOwnedBundleLoadsAndTamperRefuses() throws Exception {
        var json=new ObjectMapper();var bytes=Map.of("enforcement.html","<script src=entry.js></script>".getBytes(),"assets/entry.js","export const x=1".getBytes());
        var files=new ArrayList<Map<String,Object>>();for(var e:bytes.entrySet())files.add(Map.of("path",e.getKey(),"bytes",e.getValue().length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(e.getValue()))));
        var m=new HashMap<String,Object>();m.put("contract","ENFORCEMENT_PRODUCT_BUNDLE/v1");m.put("entry","enforcement.html");m.put("enabledPages",List.of("job-pack"));m.put("clientBindings",Map.of("VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID","unit-workspace-client","VITE_ENFORCEMENT_PRODUCT_CLIENT_ID","unit-bound-public-client"));m.put("files",files);
        byte[] manifest=json.writeValueAsBytes(m);
        assertTrue(ProductEnforcementEntryConfiguration.load(json,p->new ByteArrayInputStream(p.equals("enforcement-product-assets.json")?manifest:bytes.get(p))).allows("GET","/app/enforcement"));
        assertThrows(IllegalStateException.class,()->ProductEnforcementEntryConfiguration.load(json,p->new ByteArrayInputStream(p.equals("enforcement-product-assets.json")?manifest:"tamper".getBytes())));
        m.put("clientBindings",Map.of());byte[] unbound=json.writeValueAsBytes(m);
        assertThrows(IllegalStateException.class,()->ProductEnforcementEntryConfiguration.load(json,p->new ByteArrayInputStream(p.equals("enforcement-product-assets.json")?unbound:bytes.get(p))));
    }
}
