package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Value comparison only: these records do not authorize a principal, source, service or execution. */
class ProductComparisonMaterialValueTest {
    final ObjectMapper mapper=new ObjectMapper();
    final Instant until=Instant.parse("2026-10-08T09:00:00Z");
    ProductComparisonWorkerMaterials.Loaded material(String text,String originalRef) throws Exception {
        byte[] bytes=mapper.writeValueAsBytes(Map.of("claimsText",text));
        String sha=ProductCapturedArchive.digest(bytes),version="RAW_SHA256:"+sha;
        var original=new ProductPatentClaimOriginal(originalRef,bytes,sha,version,"claimsText",mapper);
        var ref=new ProductComparisonContracts.DocumentRef("unit-source","unit-document",version,List.of(1),null,null,null,null);
        var proof=new ProductComparisonContracts.MaterialDocument("key","unit-source","unit-document",version,"v1",sha,originalRef,"/claimsText",List.of(),null);
        var authority=new ProductComparisonDocuments.Authority(null,null,null,until);
        var document=new ProductComparisonDocuments.Loaded(ref,original,proof,authority);
        return new ProductComparisonWorkerMaterials.Loaded(new ProductComparisonInputs.View(null,"input-value",List.of()),
                original,null,document,new ProductComparisonWorkerTaskAuthority.Authorized("unit-service","unit-human","unit-task",1,1,until),until);
    }
    @Test void recreatedParserInstancesAreNotMaterialDrift() throws Exception {
        var a=material("1. Identical owned unit passage.","original.json");
        var b=material("1. Identical owned unit passage.","original.json");
        assertNotEquals(a,b);assertNotSame(a.target(),b.target());
        assertTrue(ProductComparisonWorkerMaterials.sameCurrent(a,b));
    }
    @Test void alteredBytesStillReject() throws Exception {
        assertFalse(ProductComparisonWorkerMaterials.sameCurrent(material("1. Original.","original.json"),material("1. Changed.","original.json")));
    }
    @Test void differentOriginalCoordinateStillRejects() throws Exception {
        assertFalse(ProductComparisonWorkerMaterials.sameCurrent(material("1. Same.","a.json"),material("1. Same.","b.json")));
    }
    @Test void alteredAuthorityGenerationStillRejects() throws Exception {
        var a=material("1. Same.","original.json");
        var b=new ProductComparisonWorkerMaterials.Loaded(a.input(),a.target(),a.targetPatent(),a.candidate(),
                new ProductComparisonWorkerTaskAuthority.Authorized("unit-service","unit-human","unit-task",1,2,until),until);
        assertFalse(ProductComparisonWorkerMaterials.sameCurrent(a,b));
    }
}
