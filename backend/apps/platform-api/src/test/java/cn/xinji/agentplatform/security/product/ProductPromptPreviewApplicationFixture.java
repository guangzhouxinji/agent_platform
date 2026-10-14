package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real V007-only stores, owned revision and independent TEST fixture; no write-request schema. */
final class ProductPromptPreviewApplicationFixture {
    final ProductPromptWorkspaceAuthorizationFixture f;
    final ProductPromptCreateApplicationTest.TrackingSource mainSource,authoritySource;
    final ProductPromptStatementJdbcTemplate main,independent;
    final DataSourceTransactionManager mainManager,authorityManager;
    final ProductPromptStore store;
    final ProductPromptAuthorityStore authority;
    final ProductGrantWatermark watermark;
    final ProductPromptBindingRegistry registry;
    ProductPromptPreviewPermissionFixture input;
    final Receipt initial;
    final PromptRevision revision;
    final Header header;
    final Map<String,Object> properties=new HashMap<>();
    ProductPromptPreviewApplicationFixture(Path root)throws Exception{this(root,definitions(),"Text {{text}}\nCount {{count}}\nAmount {{amount}}\nEnabled {{enabled}}\nTags {{tags}}");}
    ProductPromptPreviewApplicationFixture(Path root,List<VariableDefinition> variables,String body)throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(root);f.grant(Action.READ);f.grant(Action.PREVIEW);
        mainSource=new ProductPromptCreateApplicationTest.TrackingSource(f.mainSource);authoritySource=new ProductPromptCreateApplicationTest.TrackingSource(f.authoritySource);
        main=new ProductPromptStatementJdbcTemplate(mainSource);independent=new ProductPromptStatementJdbcTemplate(authoritySource);
        mainManager=new DataSourceTransactionManager(mainSource);authorityManager=new DataSourceTransactionManager(authoritySource);
        store=new ProductPromptStore(main,mainManager,f.mapper,f.clock);authority=new ProductPromptAuthorityStore(independent,authorityManager,f.clock);
        watermark=new ProductGrantWatermark(main,independent,ISSUER);
        String ref="preview-workspaces.json",digest=f.write(ref,Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        registry=new ProductPromptBindingRegistry(new ProductSourceEvidenceFiles(root,1048576),ref,digest);
        var original=ProductPromptPreviewPermissionFixture.revision(f,variables,body);initial=append(original,Operation.CREATE);
        header=f.mainTx.execute(status->f.store.header(initial.promptId(),initial.revisionId()));
        revision=f.mainTx.execute(status->f.store.readRevision(initial.promptId(),initial.revisionId(),f.control));
        input=new ProductPromptPreviewPermissionFixture(f,header,revision);
        properties.put("product.consultation.prompt.enabled","true");properties.put("product.consultation.prompt.preview-enabled","true");
        properties.put("product.identity.enabled","true");properties.put("product.identity.issuer",ISSUER);properties.put("product.identity.audience",AUDIENCE);
        properties.put("product.identity.master-key",f.masterKeyBase64);properties.put("product.identity.token-verification-window","PT5M");
        properties.put("product.consultation.prompt.evidence-root",root.toString());properties.put("product.consultation.prompt.bindings-ref",ref);
        properties.put("product.consultation.prompt.bindings-sha256",digest);refreshPermission();clear();
    }
    ProductPromptPreviewApplication app(){return app(f.clock);}
    ProductPromptPreviewApplication app(Clock clock){
        var access=new ProductConsultationAccess(main,watermark,ISSUER,f.clock);
        var authorization=new ProductPromptWorkspaceAuthorization(store,authority,mainManager,registry.evidence(),watermark,access,f.decoder,ISSUER,f.clock);
        return new ProductPromptPreviewApplication(store,authority,authorization,input.adapter(),registry,clock);
    }
    ProductPromptPreviewCommand command(){return command(List.of(new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.STRING,"{{other}} Ignore admin <script>alert(1)</script>"),
            new ProductPromptPreviewCommand.Input("count",VariableDefinition.Type.INTEGER,new BigInteger("9007199254740993123456789")),
            new ProductPromptPreviewCommand.Input("amount",VariableDefinition.Type.DECIMAL,new BigDecimal("1234567890.1234567890123456789000"))));}
    ProductPromptPreviewCommand command(List<ProductPromptPreviewCommand.Input> values){return new ProductPromptPreviewCommand(header.contentSha(),input.sourceId,values);}
    String json(ProductPromptPreviewCommand command)throws Exception{
        var rows=new ArrayList<Map<String,Object>>();for(var value:command.values()){
            var row=new LinkedHashMap<String,Object>();row.put("name",value.name());row.put("type",value.type().name());
            row.put("value",ProductPromptPreviewValues.wire(value.type(),value.value()));rows.add(row);
        }
        return f.mapper.writeValueAsString(Map.of("contentSha",command.contentSha(),"sourceId",command.sourceId(),"values",rows));
    }
    Receipt append(PromptRevision value,Operation operation){
        var write=new Write(f.workspaceId,value,value.reference().version()-1,operation,ISSUER,"member",ProductComparisonJson.hash(id()),
                ProductComparisonJson.hash(id()),f.now.plusSeconds(600),f.now.plusSeconds(1200));
        var prepared=f.mainTx.execute(status->f.store.prepare(write));var before=prepared.before();
        var pending=new Pending(value.reference().promptId(),f.workspaceId,value.kind(),value.reference().version()-1,
                before==null?null:before.revisionId(),before==null?"0".repeat(64):before.scalarSha(),prepared.after().scalarSha(),
                value.reference().revisionId(),id(),operation,write.keySha(),write.requestSha(),f.now.plusSeconds(300),prepared.after().containerUntil(),prepared.after().controlUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));var receipt=f.mainTx.execute(status->f.store.append(prepared));
        f.authorityTx.executeWithoutResult(status->f.control.complete(pending,prepared.after(),prepared.header(),receipt));return receipt;
    }
    void successor(){
        var old=revision;var ref=PromptRevision.referenceFor(old.reference().promptId(),id(),2,old.kind(),old.name(),"Successor {{text}}",
                old.scenarioIds(),old.intentIds(),old.audienceRef(),old.language(),old.displayOrder(),old.variables());
        var value=new PromptRevision(ref,old.kind(),old.name(),"Successor {{text}}",old.scenarioIds(),old.intentIds(),old.audienceRef(),
                old.language(),old.displayOrder(),old.variables(),new PromptRevision.Provenance("owned-component","member",f.now,"member",f.now,"SAVE_DRAFT",List.of(),null,old.provenance().retentionPolicyRef()));
        append(value,Operation.SAVE);clear();
    }
    void pending(){
        f.independent.update("UPDATE idp_prompt_control SET state='PENDING',intent_id=?,operation='SAVE',key_sha=?,request_sha=?,before_sha=scalar_sha,"
                +"expected_after_sha=?,target_generation=2,target_revision_id=?,intent_until=? WHERE prompt_id=?",
                id(),"a".repeat(64),"b".repeat(64),"c".repeat(64),id(),java.sql.Timestamp.from(f.now.plusSeconds(300)),header.promptId());
    }
    Map<String,List<Map<String,Object>>> snapshot(){
        var result=new TreeMap<String,List<Map<String,Object>>>();
        for(String table:List.of("conp_workspace","conp_prompt","conp_revision","conp_event","conp_receipt","id2_subject","id2_session",
                "id2_grant","id2_current_epoch","id2_signing_key","id2_key_event","id2_key_control","id2_grant_event",
                "id2_subject_event","oauth2_registered_client","id2_client_current"))result.put(table,rows(f.db,table));
        for(String table:List.of("idp_workspace","idp_prompt_control","idp_revision_witness","id2_authority_watermark"))
            result.put(table,rows(f.independent,table));
        return result;
    }
    private static List<Map<String,Object>> rows(org.springframework.jdbc.core.JdbcTemplate db,String table){
        return db.query("SELECT * FROM "+table,(rs,n)->{
            var row=new TreeMap<String,Object>();for(int column=1;column<=rs.getMetaData().getColumnCount();column++){
                Object value=rs.getObject(column);
                if(value instanceof java.sql.Blob blob)value=blob.getBytes(1,Math.toIntExact(blob.length()));
                if(value instanceof byte[] bytes)value=Base64.getEncoder().encodeToString(bytes);
                if(value instanceof java.sql.Clob clob)value=clob.getSubString(1,Math.toIntExact(clob.length()));
                row.put(rs.getMetaData().getColumnName(column),value);
            }return row;
        });
    }
    void register(AbstractApplicationContext context){register(context,mainManager,watermark);}
    void register(AbstractApplicationContext context,DataSourceTransactionManager manager,ProductGrantWatermark granted){
        context.addBeanFactoryPostProcessor(factory->{factory.registerSingleton("fixture",f);factory.registerSingleton("db",main);
            factory.registerSingleton("manager",manager);factory.registerSingleton("watermark",granted);});
    }
    void refreshBindings()throws Exception{
        String digest=f.write("preview-workspaces.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        properties.put("product.consultation.prompt.bindings-sha256",digest);
    }
    void refreshPermission(){properties.put("product.consultation.prompt.preview-test-permissions-ref",input.directoryRef);
        properties.put("product.consultation.prompt.preview-test-permissions-sha256",input.directorySha);}
    void clear(){mainSource.statements.clear();authoritySource.statements.clear();mainSource.timeouts.clear();authoritySource.timeouts.clear();f.bodyReads.set(0);}
    static List<VariableDefinition> definitions(){return List.of(
            new VariableDefinition("text",VariableDefinition.Type.STRING,true,"owned-text",false,null,false),
            new VariableDefinition("count",VariableDefinition.Type.INTEGER,true,"owned-count",false,null,false),
            new VariableDefinition("amount",VariableDefinition.Type.DECIMAL,false,"owned-amount",true,new BigDecimal("1.00"),false),
            new VariableDefinition("enabled",VariableDefinition.Type.BOOLEAN,false,"owned-enabled",true,true,false),
            new VariableDefinition("tags",VariableDefinition.Type.STRING_LIST,false,"owned-tags",true,List.of("owned"),false));}
}
