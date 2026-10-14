package cn.xinji.agentplatform.enforcement.deployment;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Manifest component validation; no product input or database evidence. */
class ControlledReadPolicyTest {
    @TempDir Path root;
    private final ObjectMapper mapper=new ObjectMapper();
    Map<String,Object> input() {
        var p=new LinkedHashMap<String,Object>();p.put("schemaVersion",1);p.put("approvalRef","ISOLATED_COMPONENT_APPROVAL");
        p.put("issuer","https://127.0.0.1:5444");p.put("organization","ORG-TEST");p.put("clientId","TEST-CLIENT");
        p.put("callbackUri","https://127.0.0.1:5174/app/enforcement/document-drafts");p.put("documentId","ISOLATED-DOCUMENT");
        p.put("documentRevision",1);p.put("jobPackId","ISOLATED-PACK");p.put("expiresAt","2026-10-05T00:00:00Z");
        p.put("allowConsent",false);p.put("assets",List.of("/app/assets/main-abc.js"));return p;
    }
    ControlledReadPolicy load(String text)throws Exception {
        Path file=root.resolve("policy.json");Files.writeString(file,text);return ControlledReadPolicy.load(file,sha(Files.readAllBytes(file)),mapper,ControlledReadFilterTest.CLOCK);
    }
    static String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void exactImmutableManifestLoads()throws Exception {
        var p=load(mapper.writeValueAsString(input()));assertEquals(1,p.documentRevision());
        assertThrows(UnsupportedOperationException.class,()->p.assets().add("/app/assets/other.js"));
    }
    @Test void unknownFieldRejected()throws Exception {var p=input();p.put("allowWrite",true);assertThrows(IllegalArgumentException.class,()->load(mapper.writeValueAsString(p)));}
    @Test void duplicateJsonKeysRejected()throws Exception {
        var text=mapper.writeValueAsString(input()).replace("\"allowConsent\":false","\"allowConsent\":false,\"allowConsent\":true");
        assertThrows(Exception.class,()->load(text));
    }
    @Test void wrongShaRejected()throws Exception {
        Path file=root.resolve("policy.json");Files.writeString(file,mapper.writeValueAsString(input()));
        assertThrows(IllegalArgumentException.class,()->ControlledReadPolicy.load(file,"b".repeat(64),mapper,ControlledReadFilterTest.CLOCK));
    }
    @Test void expiredManifestRejected()throws Exception {var p=input();p.put("expiresAt","2026-10-04T00:00:00Z");assertThrows(IllegalArgumentException.class,()->load(mapper.writeValueAsString(p)));}
    @Test void wildcardAndTraversalObjectsRejected()throws Exception {
        for(var id:List.of("*","../DOC","DOC/OTHER")){var p=input();p.put("documentId",id);assertThrows(IllegalArgumentException.class,()->load(mapper.writeValueAsString(p)));}
    }
    @Test void foreignCallbackAndWildcardAssetsRejected()throws Exception {
        var p=input();p.put("callbackUri","https://foreign.example/app/enforcement/document-drafts");assertThrows(IllegalArgumentException.class,()->load(mapper.writeValueAsString(p)));
        var invalid=input();invalid.put("assets",List.of("/app/assets/*"));assertThrows(IllegalArgumentException.class,()->load(mapper.writeValueAsString(invalid)));
    }
    @Test void oversizedFileRejected()throws Exception {assertThrows(IllegalArgumentException.class,()->load(" ".repeat(65537)));}
    @Test void overflowRevisionRejected()throws Exception {
        var text=mapper.writeValueAsString(input()).replace("\"documentRevision\":1","\"documentRevision\":18446744073709551617");assertThrows(IllegalArgumentException.class,()->load(text));
    }
}
