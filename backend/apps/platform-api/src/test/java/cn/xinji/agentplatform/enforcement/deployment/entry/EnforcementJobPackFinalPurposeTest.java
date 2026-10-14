package cn.xinji.agentplatform.enforcement.deployment.entry;
import cn.xinji.agentplatform.enforcement.jobpack.*;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
class EnforcementJobPackFinalPurposeTest {
    @Test void finalContextRejectsInternalPurposeBeforeCreateOrAnyExistingPackageSideEffect() throws Exception {
        var store=mock(JobPackStore.class);var db=mock(JdbcTemplate.class);var watermark=mock(ProductGrantWatermark.class);var files=mock(MaterialFiles.class);var evidence=mock(JobPackEvidenceStore.class);var tasks=mock(RealBusinessCollectionTaskStore.class);
        var controller=new EnforcementJobPackController(store,db,watermark,files,evidence,tasks,"https://unit.example.test");
        controller.finalProductAdmission(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of()));
        var jwt=Jwt.withTokenValue("unit-port-only-not-a-real-session").header("alg","RS256").subject("unit-member").build();
        assertThrows(SecurityException.class,()->controller.create(jwt,"key",new JobPackService.Create("pack","org","title","FUNCTIONAL_VALIDATION")));
        verifyNoInteractions(store,db,watermark,files,evidence,tasks);
        var snapshot=mock(JobPackContract.Snapshot.class);when(snapshot.purpose()).thenReturn("FUNCTIONAL_VALIDATION");when(store.latest("pack")).thenReturn(Optional.of(snapshot));
        assertThrows(SecurityException.class,()->controller.read(jwt,"pack"));
        assertThrows(SecurityException.class,()->controller.submit(jwt,"key","pack",JobPackContract.FactType.TASK,null));
        assertThrows(SecurityException.class,()->controller.verify(jwt,"key","pack",JobPackContract.FactType.TASK,null));
        assertThrows(SecurityException.class,()->controller.complete(jwt,"key","pack",null));
        assertThrows(SecurityException.class,()->controller.upload(jwt,"pack","ref",null));
        verifyNoInteractions(db,watermark,files,evidence,tasks);verify(store,never()).add(any());
    }
    @Test void realPurposeStillRequiresTheOriginalCurrentGrantRatherThanReceivingABoundaryBypass(){
        var store=mock(JobPackStore.class);var db=mock(JdbcTemplate.class);var watermark=mock(ProductGrantWatermark.class);var snapshot=mock(JobPackContract.Snapshot.class);when(snapshot.purpose()).thenReturn("REAL_BUSINESS_COLLECTION");when(snapshot.organization()).thenReturn("org");when(snapshot.id()).thenReturn("pack");when(store.latest("pack")).thenReturn(Optional.of(snapshot));
        var controller=new EnforcementJobPackController(store,db,watermark,mock(MaterialFiles.class),mock(JobPackEvidenceStore.class),mock(RealBusinessCollectionTaskStore.class),"https://unit.example.test");controller.finalProductAdmission(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of()));
        var jwt=Jwt.withTokenValue("unit-not-native").header("alg","RS256").issuer("https://unit.example.test").subject("unit-member").claim("jti","unit-jti").claim("sid","unit-sid").expiresAt(Instant.now().plusSeconds(60)).build();
        assertThrows(SecurityException.class,()->controller.read(jwt,"pack"));verify(store,never()).add(any());verify(watermark,atLeastOnce()).currentGeneration();
    }
    @Test void missingUnknownAndExistingInternalClaimTargetsCannotReachCurrentGrantOrWrites() {
        for (String purpose : List.of("FUNCTIONAL_VALIDATION", "UNKNOWN", "")) {
            var store=mock(JobPackStore.class); var db=mock(JdbcTemplate.class); var watermark=mock(ProductGrantWatermark.class);
            var files=mock(MaterialFiles.class); var evidence=mock(JobPackEvidenceStore.class); var tasks=mock(RealBusinessCollectionTaskStore.class);
            var controller=new EnforcementJobPackController(store,db,watermark,files,evidence,tasks,"https://unit.example.test");
            controller.finalProductAdmission(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of()));
            var jwt=Jwt.withTokenValue("unit-only").header("alg","RS256").subject("unit-member").build();
            assertThrows(SecurityException.class,()->controller.read(jwt,"missing"));
            assertThrows(SecurityException.class,()->controller.create(jwt,"key",new JobPackService.Create("pack","org","title",purpose)));
            var snapshot=mock(JobPackContract.Snapshot.class); when(snapshot.purpose()).thenReturn(purpose); when(store.latest("pack")).thenReturn(Optional.of(snapshot));
            var task=mock(RealBusinessCollectionTask.class); when(task.packageId()).thenReturn("pack"); when(tasks.find("task")).thenReturn(Optional.of(task));
            assertThrows(SecurityException.class,()->controller.claim(jwt,"key","task",new RealBusinessCollectionTask.Claim("org")));
            verify(tasks,never()).claim(anyString(),anyString(),anyString(),anyString(),anyString());
            verifyNoInteractions(db,watermark,files,evidence); verify(store,never()).add(any());
        }
    }

}
