package cn.xinji.agentplatform.security.product;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ProductComparisonParentMatrixControllerTest {
    final ProductComparisonParentEvidenceReader reader=mock(ProductComparisonParentEvidenceReader.class);
    MockMvc mvc;
    final String url="/v2/consultation/tasks/task/patent-comparisons/current/parent-reports/report";
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new ProductComparisonParentMatrixController(reader))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())).build();}
    @Test void onlyGetUsesExactCurrentInputAndReportAndNeverPost() throws Exception {
        var fixture=new ProductComparisonParentEvidenceReaderTest();fixture.setup();
        var value=fixture.reader.report("task","current","report",fixture.jwt);
        when(reader.report(eq("task"),eq("current"),eq("report"),any())).thenReturn(value);
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.comparisonId").value("current")).andExpect(jsonPath("$.previousComparisonId").value("parent"))
                .andExpect(jsonPath("$.report.result.comparisonId").value("parent")).andExpect(jsonPath("$.originalJson").value(fixture.raw))
                .andExpect(jsonPath("$.state").doesNotExist()).andExpect(jsonPath("$.configuration").doesNotExist());
        mvc.perform(post(url)).andExpect(status().isMethodNotAllowed());verify(reader,times(1)).report(eq("task"),eq("current"),eq("report"),any());
    }
    @Test void missingEvidenceReturnsNoPrivateFailureDetail() throws Exception {
        when(reader.report(anyString(),anyString(),anyString(),any())).thenThrow(new IllegalStateException("private-source"));
        mvc.perform(get(url)).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().json("{\"code\":\"COMPARISON_PARENT_MATRIX_UNAVAILABLE\"}"));
    }
    @Test void currentReadRefusalRemainsNotFound() throws Exception {
        when(reader.report(anyString(),anyString(),anyString(),any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }
}
