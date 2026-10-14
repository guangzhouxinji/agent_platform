package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Mechanical HTTP/strict JSON/immutable projection C, using the real application transaction. */
class ProductComparisonPreparationBoundaryTest extends ProductComparisonPreparationTest {
    private static org.springframework.test.web.servlet.result.StatusResultMatchers httpStatus() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
    }
    @Test void strictJsonRejectsNullDuplicatesUnknownTrailingAndCoercedValues() {
        for(String body:List.of("null","{} {}","{\"expectedTaskVersion\":1,\"expectedTaskVersion\":2}",
                "{\"unknown\":true}","{\"expectedTaskVersion\":\"1\"}","{\"confirmed\":\"true\"}")) {
            status(()->json.request(body,Confirm.class),HttpStatus.BAD_REQUEST);
        }
        assertThat(json.request(json.canonical(command),Prepare.class)).isEqualTo(command);
    }
    @Test void preparationScalarAndRetentionDriftCannotBeConsumed() {
        var prepared=prepare();
        db.update("UPDATE con5_preparation SET retention_evidence_sha=? WHERE preparation_id=?","b".repeat(64),prepared.preparationId());
        assertThatThrownBy(()->confirm(prepared,"retention-drift-C",0)).hasMessage("Comparison persisted closure or digest invalid");
        assertThat(count("con5_feature_revision")).isZero();
    }
    @Test void dynamicFeatureAndTextBudgetsRejectWithoutCreatingVersion() {
        var prepared=prepare();
        var many=java.util.stream.IntStream.range(0,201).mapToObj(i->new FeatureInput("F"+i,2,7,"Valve",null)).toList();
        for(var features:List.of(many,List.of(new FeatureInput("F",2,7,"x".repeat(65537),null)))) {
            status(()->application.confirm(goal.taskId(),new Confirm(goal.taskVersion(),prepared.preparationId(),prepared.prepareSha(),
                    0,true,"",features),"budget-C",jwt),HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(count("con5_feature_revision")).isZero();
    }
    @Test void unresolvedPatentTargetCannotBeConfirmedOrReplacedByQuery() {
        var m=candidate.material();var unresolved=new MaterialDocument(m.key(),m.sourceId(),m.documentId(),m.documentVersion(),
                m.sourceVersion(),m.rawSha(),m.originalRef(),m.jsonPointer(),List.of(),"CLAIM_DEPENDENCY_MISSING");
        org.mockito.Mockito.when(documents.read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new ProductComparisonDocuments.Loaded(candidate.reference(),candidate.original(),unresolved,candidate.authority()));
        var patent=new Prepare(goal.taskVersion(),goal.goalId(),1,goal.inputId(),"CLAIM_ALIGNMENT",new Target(null,candidate.reference()),command.candidates());
        var prepared=application.prepare(goal.taskId(),patent,jwt);assertThat(prepared.targetReady()).isFalse();
        status(()->confirm(prepared,"gap-C",0),HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(count("con5_feature_revision")).isZero();
    }
    @Test void expiredPreparationAndMissingFeatureNeverReadAsValid() {
        var prepared=prepare();var revision=confirm(prepared,"expiry-C",0);
        status(()->store.preparation(goal.taskId(),prepared.preparationId(),prepared.expiresAt()),HttpStatus.CONFLICT);
        db.update("DELETE FROM con5_feature WHERE feature_id='F2'");
        assertThatThrownBy(()->store.revision(goal.taskId(),revision.revision().revisionId())).hasMessage("Comparison persisted closure or digest invalid");
    }
    @Test void formalControllerPersistsAndReadsRevisionAndRejectsMalformedOrAnonymous() throws Exception {
        ProductSourceScopeGrantTest.script(main,"db/dm8/V101__product_comparison_prepare_request.sql");
        var mvc=MockMvcBuilders.standaloneSetup(new ProductComparisonPreparationController(application,json))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        String root="/v2/consultation/tasks/"+goal.taskId()+"/patent-comparisons";
        mvc.perform(post(root+"/prepare").header("Idempotency-Key","malformed-C").contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(httpStatus().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMPARISON_JSON"));
        mvc.perform(post(root+"/prepare").header("Idempotency-Key","prepare-http-C").contentType(MediaType.APPLICATION_JSON).content(json.canonical(command)))
                .andExpect(httpStatus().isUnauthorized());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            var response=mvc.perform(post(root+"/prepare").header("Idempotency-Key","prepare-http-C").contentType(MediaType.APPLICATION_JSON).content(json.canonical(command)))
                    .andExpect(httpStatus().isOk()).andReturn().getResponse().getContentAsString();
            var prepared=json.request(response,PreparationView.class);
            mvc.perform(post(root+"/prepare").contentType(MediaType.APPLICATION_JSON).content(json.canonical(command)))
                    .andExpect(httpStatus().isBadRequest());
            mvc.perform(post(root+"/prepare").header("Idempotency-Key","prepare-http-C")
                    .contentType(MediaType.APPLICATION_JSON).content(json.canonical(command)))
                    .andExpect(httpStatus().isOk()).andExpect(jsonPath("$.preparationId").value(prepared.preparationId()));
            mvc.perform(get(root+"/preparations/"+prepared.preparationId())).andExpect(httpStatus().isOk())
                    .andExpect(jsonPath("$.taskId").value(goal.taskId()))
                    .andExpect(jsonPath("$.requestId").value("prepare-http-C"));
            var result=mvc.perform(post(root+"/features/confirm").header("Idempotency-Key","http-C")
                    .contentType(MediaType.APPLICATION_JSON).content(json.canonical(confirmCommand(prepared,0))))
                    .andExpect(httpStatus().isOk()).andReturn().getResponse().getContentAsString();
            var revision=json.request(result,FeatureView.class);
            mvc.perform(get(root+"/features/"+revision.revision().revisionId())).andExpect(httpStatus().isOk())
                    .andExpect(jsonPath("$.packageSha").value(revision.packageSha()));
            assertThat(count("con5_feature_revision")).isEqualTo(1);
        } finally {SecurityContextHolder.clearContext();}
    }
}
