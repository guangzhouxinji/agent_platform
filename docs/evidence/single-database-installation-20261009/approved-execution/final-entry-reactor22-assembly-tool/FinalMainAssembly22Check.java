import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Evidence tool: parses real configuration definitions, never starts an application or domain bean. */
public final class FinalMainAssembly22Check {
    static final String PREFIX="cn.xinji.agentplatform.";
    static class Mappings extends RequestMappingHandlerMapping {
        // Configure the real mapping builder only; controller beans must never be instantiated by this metadata tool.
        @Override protected void initHandlerMethods() { }
        RequestMappingInfo inspect(Method method, Class<?> type) { return super.getMappingForMethod(method,type); }
    }
    static void require(boolean ok,String assertion) { if(!ok) throw new IllegalStateException(assertion); }
    static String typeName(BeanDefinition definition) {
        if(definition instanceof AnnotatedBeanDefinition annotated && annotated.getFactoryMethodMetadata()!=null)
            return annotated.getFactoryMethodMetadata().getReturnTypeName();
        return definition.getBeanClassName();
    }
    public static void main(String[] args) throws Exception {
        require(args.length==2,"EXACT_CAPABILITIES_AND_EXCLUSIVE_OUTPUT_REQUIRED");
        Path output=Path.of(args[1]);require(!Files.exists(output),"NO_EVIDENCE_OVERWRITE");
        ObjectMapper mapper=new ObjectMapper(); var catalog=mapper.readTree(Path.of(args[0]).toFile());
        List<Object> results=new ArrayList<>();
        for(var application:catalog.path("applications")) {
            String domain=application.path("domain").asText();
            var env=new StandardEnvironment();env.getPropertySources().remove("systemProperties");env.getPropertySources().remove("systemEnvironment");
            Map<String,Object> settings=new LinkedHashMap<>();application.path("fixedSettings").fields().forEachRemaining(e->settings.put(e.getKey(),e.getValue().asText()));
            env.getPropertySources().addFirst(new MapPropertySource("recorded-final-fixed-settings-only",settings));
            var factory=new DefaultListableBeanFactory();factory.setAllowBeanDefinitionOverriding(false);
            Class<?> main=Class.forName(application.path("mainClass").asText(),false,FinalMainAssembly22Check.class.getClassLoader());
            Method entry=main.getMethod("main",String[].class);require(Modifier.isStatic(entry.getModifiers())&&Modifier.isPublic(entry.getModifiers())&&entry.getReturnType()==void.class,"STANDARD_MAIN_REQUIRED_"+domain);
            var reader=new AnnotatedBeanDefinitionReader(factory,env);reader.register(main);
            var processor=new ConfigurationClassPostProcessor();processor.setEnvironment(env);processor.setResourceLoader(new DefaultResourceLoader());processor.postProcessBeanDefinitionRegistry(factory);
            Set<String> types=new TreeSet<>();List<Map<String,Object>> definitions=new ArrayList<>();
            List<Map<String,Object>> routes=new ArrayList<>();Set<String> mappingKeys=new HashSet<>();Set<String> paths=new TreeSet<>();List<String> roots=new ArrayList<>();
            Mappings mappings=new Mappings();mappings.afterPropertiesSet();
            for(String bean:factory.getBeanDefinitionNames()) {
                var definition=factory.getBeanDefinition(bean);String type=typeName(definition);
                if(type==null)continue;types.add(type);definitions.add(Map.of("bean",bean,"type",type,"factoryMethod",Objects.toString(definition.getFactoryMethodName(),"")));
                if(!type.startsWith(PREFIX))continue;
                Class<?> clazz=Class.forName(type,false,FinalMainAssembly22Check.class.getClassLoader());
                if(clazz.isAnnotationPresent(SpringBootConfiguration.class))roots.add(type);
                if(!AnnotatedElementUtils.hasAnnotation(clazz,Controller.class))continue;
                for(Method method:clazz.getDeclaredMethods()) {
                    RequestMappingInfo info=mappings.inspect(method,clazz);if(info==null)continue;
                    String signature=info.toString();require(mappingKeys.add(signature),"DUPLICATE_MVC_MAPPING_"+domain+"_"+signature);
                    var values=new TreeSet<>(info.getPatternValues());paths.addAll(values);
                    routes.add(Map.of("bean",bean,"type",type,"method",method.getName(),"paths",values,"mapping",signature));
                }
            }
            require(roots.size()==1&&roots.contains(main.getName()),"EXACTLY_OWN_BOOT_ROOT_"+domain);
            for(String required:List.of("security.product.ProductIssuerConfiguration","security.product.ProductGrantWatermarkConfiguration","security.product.ProductIdentityRouteSecurityConfiguration","runtime.AgentRuntimeConfiguration","runtime.AgentRunPersistence"))
                require(types.contains(PREFIX+required),"MISSING_REQUIRED_CONFIGURATION_"+domain+"_"+required);
            for(String required:List.of("security.product.ProductSubjects","security.product.ProductGrants"))require(types.contains(PREFIX+required),"MISSING_CURRENT_IDENTITY_DEFINITION_"+domain+"_"+required);
            if(domain.equals("consultation")) {
                require(paths.contains("/app/consultation"),"CONSULTATION_PAGE_MAPPING_REQUIRED");
                require(!types.contains(PREFIX+"enforcement.deployment.entry.ProductEnforcementEntryConfiguration"),"CONSULTATION_MUST_NOT_IMPORT_ENFORCEMENT_ENTRY");
                require(types.stream().noneMatch(t->t.startsWith(PREFIX+"enforcement.")||t.startsWith(PREFIX+"adjudication.casework.")),"CONSULTATION_BUSINESS_SCAN_CROSS_DOMAIN");
                require(paths.stream().noneMatch(p->p.startsWith("/app/enforcement")||p.startsWith("/app/adjudication")||p.startsWith("/v2/enforcement")||p.startsWith("/v2/adjudication")),"CONSULTATION_CROSS_DOMAIN_MAPPING");
            } else if(domain.equals("enforcement")) {
                for(String required:List.of("enforcement.deployment.entry.ProductEnforcementWorkspacePageController","enforcement.deployment.entry.ProductEnforcementEntryConfiguration","enforcement.deployment.entry.ProductEnforcementEntryPolicy","enforcement.jobpack.EnforcementJobPackController"))require(types.contains(PREFIX+required),"ENFORCEMENT_ENTRY_DEFINITION_REQUIRED_"+required);
                require(factory.containsBeanDefinition("productEnforcementIngress"),"ENFORCEMENT_ALL_DISPATCHER_FENCE_DEFINITION_REQUIRED");
                require(paths.contains("/")&&paths.contains("/app/enforcement")&&paths.contains("/v2/enforcement/job-packs"),"ENFORCEMENT_ROOT_JOBPACK_MAPPINGS_REQUIRED");
                require(paths.stream().noneMatch(p->p.startsWith("/app/consultation")||p.startsWith("/app/adjudication")||p.startsWith("/v2/adjudication")),"ENFORCEMENT_CROSS_DOMAIN_MAPPING");
                require(types.stream().noneMatch(t->t.startsWith(PREFIX+"adjudication.casework.")),"ENFORCEMENT_ADJUDICATION_SCAN");
            } else if(domain.equals("adjudication")) {
                require(types.contains(PREFIX+"adjudication.casework.CaseWorkConfiguration")&&types.contains(PREFIX+"adjudication.casework.CaseWorkspacePageController"),"ADJUDICATION_WORKSPACE_DEFINITION_REQUIRED");
                require(paths.contains("/app/adjudication")&&paths.contains("/v2/adjudication/case-works"),"ADJUDICATION_FORMAL_MAPPINGS_REQUIRED");
                require(types.stream().noneMatch(t->t.startsWith(PREFIX+"enforcement.")||t.startsWith(PREFIX+"consultation.")),"ADJUDICATION_CROSS_DOMAIN_BUSINESS_DEFINITIONS");
                require(paths.stream().noneMatch(p->p.startsWith("/app/enforcement")||p.startsWith("/app/consultation")||p.startsWith("/v2/enforcement")||p.startsWith("/v2/consultation")),"ADJUDICATION_CROSS_DOMAIN_MAPPING");
            } else throw new IllegalStateException("UNKNOWN_DOMAIN");
            require(!types.contains(PREFIX+"web.ReactFrontendController"),"GENERIC_DEVELOPMENT_FRONTEND_MUST_NOT_BE_IMPORTED_"+domain);
            List<String> singletons=Arrays.asList(factory.getSingletonNames());
            for(String bean:singletons) {
                Object singleton=factory.getSingleton(bean);require(singleton==null||!singleton.getClass().getName().startsWith(PREFIX),"NO_DOMAIN_SINGLETON_INSTANTIATION_"+domain);
                require(!(singleton instanceof javax.sql.DataSource),"NO_DATASOURCE_INSTANTIATION_"+domain);
            }
            results.add(Map.of("domain",domain,"mainClass",main.getName(),"bootRoots",roots,"fixedSettings",settings,"beanDefinitionCount",factory.getBeanDefinitionCount(),"definitions",definitions,"mvcRoutes",routes,"frameworkSingletonNames",singletons,"assertionsPassed",true));
        }
        require(results.size()==3,"ALL_THREE_DOMAINS_REQUIRED");
        Map<String,Object> evidence=Map.of("format","CURRENT_THREE_MAIN_CONFIGURATION_DEFINITION_ASSEMBLY/v1","domains",results,"contextRefreshed",false,"applicationStarted",false,"databaseConnected",false,"clientRegistered",false,"scope","REAL_SPRING_CONFIGURATION_SCAN_IMPORT_AND_MVC_METADATA; NOT_BEAN_INITIALIZATION_OR_RUNTIME_OR_FULL_TESTS");
        Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),StandardOpenOption.CREATE_NEW);
        System.out.println("All three actual main configuration-definition assemblies passed; no runtime beans, database or application started.");
    }
}
