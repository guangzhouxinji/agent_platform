package cn.xinji.agentplatform.security.product;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.server.ResponseStatusException;

/** Real MVC serialization/error mapping with controlled domain port, not deployed JWT/DM8. */
class ProductComparisonLineageControllerTest {
    final ProductComparisonLineageProjection reader=mock(ProductComparisonLineageProjection.class);
    MockMvc mvc;
    final String url="/v2/consultation/tasks/task/patent-comparisons/current/lineage";
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new ProductComparisonLineageController(reader))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();}
    @Test void getSerializesOnlyReferencesAndDoesNotAcceptPost() throws Exception {
        var view=new ProductComparisonLineageProjection.View("task","current","a".repeat(64),"parent","b".repeat(64),"v1","c".repeat(64),Instant.parse("2026-10-12T00:01:00Z"),List.of(),List.of(),List.of(),List.of(),List.of(new ProductComparisonParentEvidenceReader.Ancestor("parent","b".repeat(64),null)));
        when(reader.read(eq("task"),eq("current"),any())).thenReturn(view);
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.previousComparisonId").value("parent")).andExpect(jsonPath("$.carried").isArray())
                .andExpect(jsonPath("$.ancestry[0].comparisonId").value("parent")).andExpect(jsonPath("$.ancestry[0].inputSha").value("b".repeat(64)))
                .andExpect(jsonPath("$.configuration").doesNotExist()).andExpect(jsonPath("$.state").doesNotExist());
        mvc.perform(post(url)).andExpect(status().isMethodNotAllowed());verify(reader,times(1)).read(eq("task"),eq("current"),any());
    }
    @Test void missingEvidenceIsGenericConflictNotPlaintextOrInternalDetail() throws Exception {
        when(reader.read(anyString(),anyString(),any())).thenThrow(new IllegalStateException("private-file-and-source"));
        mvc.perform(get(url)).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().json("{\"code\":\"COMPARISON_LINEAGE_UNAVAILABLE\"}"));
    }
    @Test void permissionRefusalRetainsNotFoundInsteadOfConflict() throws Exception {
        when(reader.read(anyString(),anyString(),any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }
}
