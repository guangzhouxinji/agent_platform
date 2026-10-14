package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProductSubjectRecoveryControllerTest {
    final ProductMaintenanceProof proof=mock(ProductMaintenanceProof.class);
    final ProductSubjects subjects=mock(ProductSubjects.class);
    final ProductIdentityController controller=new ProductIdentityController(proof,subjects,null,null,null,null,null,new ObjectMapper());
    final String id=UUID.randomUUID().toString(), key=UUID.randomUUID().toString();
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final MockHttpServletResponse response=new MockHttpServletResponse();
    @Test void rechecksSubjectsEachTimeAndOmitsSecretFromHistoricalReceipt(){
        when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("actor");
        when(subjects.recoverActivation(id,1,"actor",key)).thenReturn(new ProductSubjects.Recovery(id,key,2,
            Instant.parse("2026-10-10T01:00:00Z"),"NOT_REDELIVERED",null));
        var result=controller.recoverActivation(id,"{\"expectedCredentialVersion\":1}",key,request,response);
        assertThat(result).doesNotContainKey("oneTimeActivation").containsEntry("delivery","NOT_REDELIVERED");
        controller.recoverActivation(id,"{\"expectedCredentialVersion\":1}",key,request,response);
        verify(proof,times(2)).require(request,ProductMaintenanceProof.Role.SUBJECTS);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void firstResponseIncludesOnlyApprovedFields(){
        when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("actor");
        when(subjects.recoverActivation(id,1,"actor",key)).thenReturn(new ProductSubjects.Recovery(id,key,2,
            Instant.parse("2026-10-10T01:00:00Z"),"FIRST_RESPONSE","test-only-random-value"));
        var view=controller.recoverActivation(id,"{\"expectedCredentialVersion\":1}",key,request,response);
        assertThat(view).containsOnlyKeys("subjectId","requestId","credentialVersion","expiresAt","delivery","oneTimeActivation");
    }
    @Test void maintenanceDeniedNeverCallsSubjectAndRetainsNoStore(){
        when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->controller.recoverActivation(id,"{}",key,request,response)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(subjects); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void strictBodyRejectsExtrasDuplicatesMissingWrongTypesAndTrailingData(){
        when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("actor");
        for(String body:new String[]{"{}","null","[]","{\"expectedCredentialVersion\":\"1\"}",
            "{\"expectedCredentialVersion\":1.5}","{\"expectedCredentialVersion\":null}",
            "{\"expectedCredentialVersion\":1,\"ttl\":600}","{\"expectedCredentialVersion\":1,\"password\":\"x\"}",
            "{\"expectedCredentialVersion\":1,\"expectedCredentialVersion\":2}",
            "{\"expectedCredentialVersion\":9223372036854775808}","not-json","{\"expectedCredentialVersion\":1} {}"})
            assertThatThrownBy(()->controller.recoverActivation(id,body,key,request,response)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(subjects);
    }
    private org.springframework.test.web.servlet.MockMvc http() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                new ObjectMapper().findAndRegisterModules())).build();
    }
    @Test void annotatedPostRouteReturnsHistoricalMetadataWithoutSecret() throws Exception {
        when(proof.require(any(),eq(ProductMaintenanceProof.Role.SUBJECTS))).thenReturn("actor");
        when(subjects.recoverActivation(id,1,"actor",key)).thenReturn(new ProductSubjects.Recovery(id,key,2,
            Instant.parse("2026-10-10T01:00:00Z"),"NOT_REDELIVERED",null));
        http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/v2/identity/subjects/"+id+"/activation-recovery").header("Idempotency-Key",key)
            .contentType("application/json").content("{\"expectedCredentialVersion\":1}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.delivery").value("NOT_REDELIVERED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.oneTimeActivation").doesNotExist());
    }
    @Test void formalRouteConflictAndStorageErrorsHaveEmptyNonsecretBody() throws Exception {
        when(proof.require(any(),eq(ProductMaintenanceProof.Role.SUBJECTS))).thenReturn("actor");
        var mvc=http();
        for(RuntimeException failure:new RuntimeException[]{new IllegalStateException("internal-detail"),
                new org.springframework.dao.DataAccessResourceFailureException("driver-private-detail")}){
            doThrow(failure).when(subjects).recoverActivation(id,1,"actor",key);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/v2/identity/subjects/"+id+"/activation-recovery").header("Idempotency-Key",key)
                .contentType("application/json").content("{\"expectedCredentialVersion\":1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .is(failure instanceof IllegalStateException?409:503))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        }
    }
    @Test void formalRouteMalformedExtraFields400AndMaintenanceDenied403() throws Exception {
        var mvc=http(); when(proof.require(any(),eq(ProductMaintenanceProof.Role.SUBJECTS))).thenReturn("actor");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/v2/identity/subjects/"+id+"/activation-recovery").header("Idempotency-Key",key)
            .contentType("application/json").content("{\"expectedCredentialVersion\":1,\"ttl\":600}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        when(proof.require(any(),eq(ProductMaintenanceProof.Role.SUBJECTS)))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/v2/identity/subjects/"+id+"/activation-recovery").header("Idempotency-Key",key)
            .contentType("application/json").content("{\"expectedCredentialVersion\":1}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(subjects);
    }

}
