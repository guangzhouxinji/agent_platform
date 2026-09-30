package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.xinji.agentplatform.consultation.adapter.out.synthetic.SyntheticPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.synthetic.SyntheticCaseLibraryAdapter;
import cn.xinji.agentplatform.consultation.center.adapter.in.web.SyntheticCenterController;
import cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.synthetic.SyntheticPatentLibraryAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.trademarklibrary.adapter.out.synthetic.SyntheticTrademarkLibraryAdapter;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.synthetic.SyntheticTrademarkCatalogAdapter;
import cn.xinji.agentplatform.enforcement.adapter.in.web.EnforcementSourceManagementController;
import cn.xinji.agentplatform.consultation.patentassistant.adapter.in.web.PatentAssistantController;
import cn.xinji.agentplatform.web.DevelopmentOperationalSourceController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class SyntheticRuntimeProfileBoundaryTest {
    @Test
    void everySyntheticRuntimeComponentRequiresTheTestOnlyProfile() throws ClassNotFoundException {
        List<Class<?>> syntheticComponents = List.of(
                SyntheticPatentSearchAdapter.class,
                SyntheticCaseLibraryAdapter.class,
                SyntheticCenterController.class,
                SyntheticPatentLibraryAdapter.class,
                SyntheticPatentCatalogAdapter.class,
                SyntheticTrademarkLibraryAdapter.class,
                SyntheticTrademarkCatalogAdapter.class,
                DevelopmentOperationalSourceController.class,
                EnforcementSourceManagementController.class,
                PatentAssistantController.class);

        syntheticComponents.forEach(component ->
                assertArrayEquals(new String[] {"test-synthetic"}, component.getAnnotation(Profile.class).value(), component.getName()));
        for (String name : List.of(
                "DevComparisonInputPreviewController", "DevCorrectionController", "DevCurrentWorkStateController",
                "DevEvidenceReadController", "DevFeatureConfirmationController", "DevHumanAcceptanceController",
                "DevHumanContextController", "DevHumanOpinionController", "DevHumanOpinionSubmissionController",
                "DevHumanRequestController", "DevHumanReturnController", "DevHumanReturnSubmissionController",
                "DevPurposeSelectionController", "DevPurposeSelectionWriteController", "DevTaskCoreController",
                "DevTextResultController")) {
            Class<?> component = Class.forName("cn.xinji.agentplatform.security.v2." + name);
            assertArrayEquals(new String[] {"test-synthetic"}, component.getAnnotation(Profile.class).value(), name);
        }
    }

    @Test
    void testResourcesExplicitlyActivateTheSyntheticFixtureProfile() throws Exception {
        String testConfiguration = Files.readString(Path.of("backend/apps/platform-api/src/test/resources/application.yml"));
        assertEquals(true, testConfiguration.contains("active: test-synthetic"));
    }
}
