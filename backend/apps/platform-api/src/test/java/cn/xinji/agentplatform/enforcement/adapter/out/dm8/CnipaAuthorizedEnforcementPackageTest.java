package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CnipaAuthorizedEnforcementPackageTest {
    private static final Path PACKAGE = Path.of("data", "enforcement", "authorized", "cnipa-official-v1");

    @Test void validatesTheReviewedFiveFilePackageWithoutInventingCurrentRightsOrEffectiveDate() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var value = new AuthorizedEnforcementPackageValidator(mapper).load(PACKAGE);

        assertThat(value.manifest().batchId()).isEqualTo("ENF-CNIPA-OFFICIAL-AUTHORIZED-V1");
        assertThat(value.manifest().retentionPolicy()).isEqualTo("UNTIL_DEVELOPMENT_PILOT_END");
        assertThat(value.manifest().retentionUntil()).isNull();
        assertThat(value.manifest().externalModelAllowed()).isFalse();
        assertThat(value.clues()).singleElement().satisfies(clue -> {
            assertThat(clue.source().sourceId()).isEqualTo("CNIPA-HISTORICAL-GUIDANCE-CASE-1");
            assertThat(clue.missingItems()).contains("UNDERLYING_CASE_EVIDENCE", "CURRENT_RIGHT_STATUS");
        });
        assertThat(value.evidence()).singleElement().satisfies(material -> {
            assertThat(material.relationType()).isEqualTo("MISSING");
            assertThat(material.issue()).isEqualTo("PUBLIC_OFFICIAL_DOCUMENT_NOT_UNDERLYING_CASE_EVIDENCE");
        });
        assertThat(value.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.effectivityStatus()).isEqualTo("NO_EXPLICIT_EFFECTIVE_DATE");
            assertThat(rule.effectiveFrom()).isNull();
            assertThat(rule.applicability()).contains("追加被请求人");
        });
        assertThat(value.rightStatuses()).isEmpty();
        assertThat(value.recordCount()).isEqualTo(3);
    }

    @Test void converterHasNoNetworkModelDatabaseOrDecisionSideEffects() throws Exception {
        String script = Files.readString(Path.of("scripts", "build-cnipa-authorized-enforcement-package.mjs"));

        assertThat(script).doesNotContain("fetch(", "node:https", "node:http", "jdbc:", "dm.jdbc",
                "OpenAI", "DeepSeek", "officialState", "reviewDecisionWrite");
        assertThat(script).contains("automaticEvidenceAcceptanceAllowed === false",
                "automaticLegalDecisionAllowed === false", "RIGHT_STATUS_MUST_BE_EMPTY");
    }
}
