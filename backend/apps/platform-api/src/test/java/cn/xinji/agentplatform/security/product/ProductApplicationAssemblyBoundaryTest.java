package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Assembly boundary only, not live DM8/application deployment proof. */
class ProductApplicationAssemblyBoundaryTest {
    @Test void consultationCannotRecursivelyImportEnforcementBootConfiguration() {
        var scan = ProductConsultationApplication.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan);
        assertTrue(Arrays.stream(scan.excludeFilters()).anyMatch(f ->
                f.type() == FilterType.ANNOTATION && Arrays.asList(f.classes()).contains(SpringBootConfiguration.class)));
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootConfiguration.class));
        scanner.addExcludeFilter(new AnnotationTypeFilter(SpringBootConfiguration.class));
        assertTrue(scanner.findCandidateComponents(ProductIssuerConfiguration.class.getPackageName()).isEmpty());
        assertNotNull(ProductEnforcementApplication.class.getAnnotation(SpringBootConfiguration.class));
    }
    @Test void eachFormalRuntimeHasAStandardIndependentMain() throws Exception {
        for (var type : java.util.List.of(ProductConsultationApplication.class, ProductEnforcementApplication.class,
                cn.xinji.agentplatform.adjudication.casework.ProductAdjudicationApplication.class)) {
            var main = type.getMethod("main", String[].class);
            assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()));
            assertEquals(void.class, main.getReturnType());
        }
    }
}
