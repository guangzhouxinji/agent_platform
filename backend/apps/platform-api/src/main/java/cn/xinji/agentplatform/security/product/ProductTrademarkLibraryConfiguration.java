package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.ProductTrademarkLibraryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.trademark-read.enabled",havingValue="true")
class ProductTrademarkLibraryConfiguration {
    record Assembly(ProductTrademarkReadTransaction boundary,ProductTrademarkLibraryService service) { }
    private final Environment environment;
    ProductTrademarkLibraryConfiguration(Environment environment) {
        this.environment=environment;
        for(String key:List.of("product.identity.enabled","product.source.engine.enabled"))
            require(Boolean.TRUE.equals(environment.getProperty(key,Boolean.class)),Code.CAPABILITY_UNAVAILABLE);
    }
    @Bean Assembly productTrademarkReads(JdbcTemplate db,PlatformTransactionManager manager,ProductGrantWatermark grants,
            ProductConsultationAccess access,JwtDecoder decoder,ProductSourceEvidenceFiles files,ObjectMapper mapper) {
        require(manager instanceof DataSourceTransactionManager,Code.CAPABILITY_UNAVAILABLE);
        String issuer=environment.getRequiredProperty("product.identity.issuer");
        var pins=pins(mapper,environment.getRequiredProperty("product.consultation.trademark-read.approval-pins-json"));
        // Fail startup on absent product storage; legacy PUBLIC_LOCAL data never substitutes for this schema.
        for(String table:List.of("con1tm_capture_request","con1tm_record_binding","kb_trademark_party"))
            db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE 1=0",Integer.class);
        var readDb=new JdbcTemplate(db.getDataSource());readDb.setQueryTimeout(5);
        Clock clock=Clock.systemUTC();var store=new ProductTrademarkProjectionStore(readDb,mapper,clock);
        var captures=new ProductTrademarkReadCaptures(readDb,grants.independentDatabase(),files,mapper,clock,pins);
        var boundary=new ProductTrademarkReadTransaction(readDb,(DataSourceTransactionManager)manager,grants,access,decoder,issuer,clock,captures,store);
        var source=new ProductDm8TrademarkLibrarySource(boundary,new ProductTrademarkProjectionQuery(readDb,store,clock),clock);
        return new Assembly(boundary,new ProductTrademarkLibraryService(boundary,source,clock));
    }
    static List<ProductTrademarkProjectionApproval.Pin> pins(ObjectMapper mapper,String value) {
        require(value!=null&&value.length()<=32768,Code.CAPABILITY_UNAVAILABLE);
        require(StandardCharsets.UTF_8.newEncoder().canEncode(value),Code.CAPABILITY_UNAVAILABLE);
        com.fasterxml.jackson.databind.JsonNode array;
        try{array=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new Failure(Code.CAPABILITY_UNAVAILABLE);}
        require(array!=null,Code.CAPABILITY_UNAVAILABLE);
        require(array.isArray()&&!array.isEmpty()&&array.size()<=8,Code.CAPABILITY_UNAVAILABLE);
        var pins=new ArrayList<ProductTrademarkProjectionApproval.Pin>();
        for(var node:array) {
            var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);
            require(names.equals(Set.of("sourceId","contractVersion","capabilityRef","capabilitySHA","approvalEvidenceRef","approvalEvidenceSHA","approvedScopeSHA")),Code.CAPABILITY_UNAVAILABLE);
            pins.add(new ProductTrademarkProjectionApproval.Pin(ProductTrademarkCaptureJson.text(node.get("sourceId")),ProductTrademarkCaptureJson.text(node.get("contractVersion")),
                    ProductTrademarkCaptureJson.text(node.get("capabilityRef")),ProductTrademarkCaptureJson.text(node.get("capabilitySHA")),ProductTrademarkCaptureJson.text(node.get("approvalEvidenceRef")),
                    ProductTrademarkCaptureJson.text(node.get("approvalEvidenceSHA")),ProductTrademarkCaptureJson.text(node.get("approvedScopeSHA"))));
        }
        return List.copyOf(pins);
    }
}
