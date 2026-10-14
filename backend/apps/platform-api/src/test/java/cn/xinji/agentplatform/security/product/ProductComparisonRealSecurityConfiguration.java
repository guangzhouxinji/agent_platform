package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Explicit product composition: no component scan, mock authorization or synthetic JWT filter. */
@TestConfiguration(proxyBeanMethods=false)
@Profile("comparison-real-security")
@EnableAutoConfiguration
@Import({ProductIssuerConfiguration.class, ProductGrantWatermarkConfiguration.class,
        ProductIdentityRouteSecurityConfiguration.class, ExistingRouteSecurityConfiguration.class,
        ProductIdentityTransportFilter.class, ProductIdentityController.class, ProductClientController.class,
        ProductConsultationTaskConfiguration.class, ProductConsultationTaskController.class,
        ProductWorkloadController.class, ProductSourceCandidateConfiguration.class,
        ProductSourceCandidateController.class, ProductSourceEngineConfiguration.class,
        ProductSourceVerificationController.class, ProductSourceAdmissionController.class, ProductSourceScopeController.class, ProductComparisonSchemaConfiguration.class,
        ProductComparisonPreparationConfiguration.class, ProductComparisonPreparationController.class,
        ProductComparisonInputConfiguration.class, ProductComparisonInputController.class,
        ProductComparisonWorkerDelegationController.class})
class ProductComparisonRealSecurityConfiguration { }

/** Optional zero-model product composition; legacy imports remain unchanged. */
@TestConfiguration(proxyBeanMethods=false)
@Profile("comparison-real-security")
@Import({ProductComparisonRealSecurityConfiguration.class, ProductComparisonScopeController.class,
        ProductComparisonMatrixConfiguration.class, ProductComparisonMatrixController.class,
        ProductComparisonStartConfiguration.class, ProductComparisonStartController.class,
        ProductComparisonHandoffController.class, ProductComparisonDocumentCatalogController.class,
        ProductComparisonWorkbenchContextController.class, ProductRetrievalDialogueConfiguration.class,
        ProductRetrievalWorkspaceConfiguration.class, ProductRetrievalWorkspaceController.class,
        ProductConsultationWorkspacePageController.class})
class ProductComparisonRealSecurityStartConfiguration { }
