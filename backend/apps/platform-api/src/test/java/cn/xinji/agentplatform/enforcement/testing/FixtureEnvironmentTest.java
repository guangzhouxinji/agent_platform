package cn.xinji.agentplatform.enforcement.testing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FixtureEnvironmentTest {
    @Test void standaloneLegacyFixtureIsUnchangedWhenNoNamespaceWasRequested() {
        assertEquals("legacy",FixtureEnvironment.required("URL","ISOLATED_",Map.of("URL","legacy")));
    }
    @Test void explicitNamespaceWinsWithoutMixingHistoricalConnection() {
        assertEquals("owned",FixtureEnvironment.required("URL","ISOLATED_",Map.of("URL","legacy","ISOLATED_URL","owned")));
    }
    @Test void partialNamespaceNeverFallsBackToHistoricalPasswordOrUrl() {
        assertThrows(IllegalStateException.class,()->FixtureEnvironment.required("PASSWORD","ISOLATED_",Map.of("PASSWORD","legacy","ISOLATED_URL","owned")));
        assertThrows(IllegalStateException.class,()->FixtureEnvironment.required("URL","ISOLATED_",Map.of("URL","legacy","ISOLATED_PASSWORD","owned")));
    }
    @Test void blankIsolatedValueDoesNotFallBack() {
        assertThrows(IllegalStateException.class,()->FixtureEnvironment.required("URL","ISOLATED_",Map.of("URL","legacy","ISOLATED_URL"," ")));
    }
    @Test void optionalHistoricalRevocationFlagIsNotInherited() {
        assertNull(FixtureEnvironment.optional("REVOKE","ISOLATED_",Map.of("REVOKE","true","ISOLATED_URL","owned")));
    }
    @Test void otherFixtureNamespaceCannotRedirectThisFixture() {
        assertEquals("legacy",FixtureEnvironment.required("URL","JOB_",Map.of("URL","legacy","GRANT_URL","owned")));
    }
}
