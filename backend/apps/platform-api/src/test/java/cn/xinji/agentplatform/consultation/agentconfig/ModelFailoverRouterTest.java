package cn.xinji.agentplatform.consultation.agentconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter;
import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter.ModelFailure;
import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter.RoutingFailure;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelFailoverRouterTest {
    private final ModelFailoverRouter router = new ModelFailoverRouter();

    @Test void switchesInConfiguredOrderForTechnicalFailure() {
        var result = router.execute(List.of("primary", "backup"), Set.of("TIMEOUT"), model -> {
            if (model.equals("primary")) throw new ModelFailure("TIMEOUT");
            return "answer";
        });
        assertEquals("backup", result.selectedModelId());
        assertEquals(List.of("TIMEOUT", "SUCCEEDED"), result.attempts().stream().map(ModelFailoverRouter.Attempt::outcome).toList());
    }

    @Test void neverUsesFallbackToBypassAuthorizationOrDataPolicy() {
        RoutingFailure failure = assertThrows(RoutingFailure.class, () -> router.execute(
                List.of("primary", "backup"), Set.of("TIMEOUT", "UPSTREAM_UNAVAILABLE"),
                model -> { throw new ModelFailure("ACCESS_DENIED"); }));
        assertEquals("ACCESS_DENIED", failure.code());
        assertEquals(1, failure.attempts().size());
    }

    @Test void reportsExhaustedChainWithoutInventingAResult() {
        RoutingFailure failure = assertThrows(RoutingFailure.class, () -> router.execute(
                List.of("primary", "backup"), Set.of("RATE_LIMITED"),
                model -> { throw new ModelFailure("RATE_LIMITED"); }));
        assertEquals("MODEL_CHAIN_EXHAUSTED", failure.code());
        assertEquals(2, failure.attempts().size());
    }
}
