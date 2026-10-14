package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.*;
import cn.xinji.agentplatform.enforcement.documentrule.*;
import cn.xinji.agentplatform.enforcement.material.*;
import cn.xinji.agentplatform.enforcement.evidence.*;
import cn.xinji.agentplatform.enforcement.legalreference.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only explanation of the existing frozen generation gates, not a second status ledger. */
public final class DocumentDraftInputAudit {
    public record Check(String category,String objectId,long revision,String status,String code,String reason,String sha256) { }
    public record Report(String organization,Type type,Instant checkedAt,List<Input> inputs,List<Check> checks) {
        public Report {inputs=List.copyOf(inputs);checks=List.copyOf(checks);}
    }
    private final RuntimeDocumentDraftInputs runtime;private final JdbcTemplate db;private final MaterialAccess access;
    private final ObjectMapper json;private final Path caseFile,catalogFile;private final DocumentProfileCatalog profiles;
    private final DocumentTemplateStore templates;private final Dm8DocumentRuleStore rules;private final LegalReferenceStore legal;
    private final MaterialRepository materials;private final MaterialFiles files;private final VerifiedEvidenceSource evidence;
    public DocumentDraftInputAudit(RuntimeDocumentDraftInputs runtime,JdbcTemplate db,MaterialAccess access,ObjectMapper json,
            Path caseFile,Path catalogFile,DocumentProfileCatalog profiles,DocumentTemplateStore templates,Dm8DocumentRuleStore rules,
            LegalReferenceStore legal,MaterialRepository materials,MaterialFiles files,VerifiedEvidenceSource evidence) {
        this.runtime=runtime;this.db=db;this.access=access;this.json=json;this.caseFile=caseFile;this.catalogFile=catalogFile;this.profiles=profiles;
        this.templates=templates;this.rules=rules;this.legal=legal;this.materials=materials;this.files=files;this.evidence=evidence;
    }
    public Report inspect(String actor,String org) {
        var checks=new ArrayList<Check>();var choices=new ArrayList<Input>();
        var materialRefs=new ArrayList<ObjectVersion>();var legalRefs=new ArrayList<ObjectVersion>();ObjectVersion caseRef=null;
        add(checks,"Conduct","",0,"NOT_APPLICABLE","TYPE_BRANCH","事实记录草稿不强制行为复核输入；另外两类按冻结D消费",null);
        add(checks,"Procedure","",0,"NOT_APPLICABLE","TYPE_BRANCH","事实记录只消费已有映射字段；不新增程序报告前置门禁",null);
        try {
            byte[] raw=Files.readAllBytes(caseFile);var context=json.readTree(raw);
            if(!org.equals(context.path("organization").asText()))throw new SecurityException("Case organization mismatch");
            caseRef=new ObjectVersion(context.path("caseRef").asText(),Long.parseLong(context.path("version").asText()),sha(raw));
            if(!allowed(actor,org,"CASE",caseRef.id(),caseRef.revision(),"DOCUMENT_DRAFT",checks))throw new SecurityException("Case read denied");
            add(checks,"案件/案件来源",caseRef.id(),caseRef.revision(),"PASSED","CASE_SNAPSHOT","保留原始案件快照；数据性质="+context.path("nature").asText(context.path("testNature").asText("未标注；不推定现实案件")),caseRef.sha256());
            add(checks,"组织关系",caseRef.id(),caseRef.revision(),"PASSED","SAME_ORGANIZATION","案件快照组织与当前授权组织一致",null);
            for(var node:context.path("materials")) {
                var ref=new ObjectVersion(node.path("id").asText(),Long.parseLong(node.path("version").asText()),node.path("sha256").asText());
                if(!allowed(actor,org,"MATERIAL",ref.id(),ref.revision(),"DOCUMENT_DRAFT",checks))continue;
                var material=materials.material(ref.id()).orElseThrow(()->new IllegalStateException("Material missing"));
                boolean current=org.equals(material.organization())&&material.version()==ref.revision()&&"VERIFIED".equals(material.state())&&material.uses().contains("ANALYZE")&&material.expiresAt().isAfter(Instant.now());
                add(checks,"Evidence",ref.id(),ref.revision(),current?"PASSED":"FAILED","MATERIAL_STATE_VERSION","状态="+material.state()+"；DB版本="+material.version()+"；案件版本="+ref.revision()+"；同组织="+org.equals(material.organization())+"；用途="+material.uses()+"；有效至="+material.expiresAt()+"；未过期="+material.expiresAt().isAfter(Instant.now()),ref.sha256());
                boolean original;
                try {original=ref.sha256().equals(material.sha256())&&ref.sha256().equals(sha(files.read(material.fileKey())));}
                catch(RuntimeException missing){add(checks,"Evidence原件",ref.id(),ref.revision(),"FAILED","MATERIAL_ORIGINAL_UNAVAILABLE","DB有材料坐标，但当前文件根不能读取原件；案件快照存在不等于原件可用",ref.sha256());continue;}
                add(checks,"Evidence原件",ref.id(),ref.revision(),original?"PASSED":"FAILED","MATERIAL_ORIGINAL_SHA","案件引用、DB与原件SHA必须一致",ref.sha256());
                if(current&&original)materialRefs.add(ref);
            }
            if(materialRefs.isEmpty())add(checks,"Evidence","",0,"FAILED","NO_CONSUMABLE_EVIDENCE","没有当前可读、同版且原件完整的材料",null);
            else try {evidence.read(caseRef.id(),Long.toString(caseRef.revision()),materialRefs.stream().map(v->new EvidenceTrialSource.Ref(v.id(),Long.toString(v.revision()),v.sha256())).toList());
                add(checks,"Evidence采集/核验链",caseRef.id(),caseRef.revision(),"PASSED","VERIFIED_CHAIN","真实UPLOAD/COLLECT_ATTEST/异人VERIFY及现时来源水位校验通过",null);
            }catch(SecurityException|IllegalStateException failure){add(checks,"Evidence采集/核验链",caseRef.id(),caseRef.revision(),"FAILED","VERIFIED_CHAIN_UNAVAILABLE","采集核验、来源准入或原件门禁未通过；不得按普通文件绕过",null);materialRefs.clear();}
        }catch(SecurityException denied){caseRef=null;materialRefs.clear();add(checks,"案件/组织/授权","",0,"FAILED","CASE_UNAVAILABLE","当前案件组织或读取授权不匹配；不披露未授权坐标",null);}
        catch(Exception failure){add(checks,"案件/案件来源","",0,"FAILED","CASE_ORIGINAL_UNAVAILABLE","案件快照、材料原件或精确版本不可读取",null);}
        if(caseRef==null){
            add(checks,"Evidence","",0,"PENDING","EVIDENCE_NOT_INSPECTED","案件读取未通过，不能披露或验证其材料；不是已验证无材料",null);
            add(checks,"组织关系","",0,"PENDING","CASE_ORGANIZATION_NOT_VERIFIED","案件读取未通过，不能断言组织或来源关系已匹配",null);
        }
        var refs=db.query("SELECT reference_id,revision FROM enf9_legal_reference r WHERE organization_id=? AND revision=(SELECT MAX(revision) FROM enf9_legal_reference x WHERE x.reference_id=r.reference_id)",(rs,n)->new Object[]{rs.getString(1),rs.getLong(2)},org);
        for(var coordinate:refs) {
            String id=(String)coordinate[0];long version=(Long)coordinate[1];if(!allowed(actor,org,"LEGAL_REFERENCE",id,version,"LEGAL_REFERENCE",checks))continue;
            try {var ref=legal.reference(id,version).orElseThrow();var search=legal.search(ref.searchId(),ref.searchRevision()).orElseThrow();
                boolean bound=caseRef!=null&&caseRef.id().equals(search.caseRef())&&ref.reviewState()==LegalReferenceContract.ReviewState.CONFIRMED&&ref.bindingState()==LegalReferenceContract.BindingState.ACTIVE;
                add(checks,"法律引用版本",id,version,bound?"PASSED":"FAILED","LEGAL_CASE_STATE","审核="+ref.reviewState()+"；绑定="+ref.bindingState()+"；同一案件="+(caseRef!=null&&caseRef.id().equals(search.caseRef())),ref.verbatimSha256());
                if(bound)legalRefs.add(new ObjectVersion(id,version,ref.verbatimSha256()));
            }catch(RuntimeException failure){add(checks,"法律引用版本",id,version,"FAILED","LEGAL_ORIGINAL_UNAVAILABLE","当前引用或原始搜索版本不可读取",null);}
        }
        if(legalRefs.isEmpty())add(checks,"法律引用版本","",0,"PENDING","NO_CURRENT_CASE_REFERENCE","当前案件没有可读、CONFIRMED/ACTIVE的同源法律引用；事实记录只允许保存显式缺项的不完整稿，不允许提交审核或导出",null);
        configuredSources(actor,org,caseRef,materialRefs,checks);
        int factTemplates=0;
        for(var template:templates.list(org)) {
            if(!allowed(actor,org,"DOCUMENT_TEMPLATE",template.templateId(),template.currentRevision(),"DOCUMENT_TEMPLATE",checks))continue;
            var exact=templates.revision(template.templateId(),template.currentRevision()).orElseThrow();
            var matches=profiles.all().stream().filter(p->Objects.equals(runtime.profileBinding(p),exact.approvalRef())).toList();
            add(checks,"模板版本/发布状态",exact.templateId(),exact.revision(),exact.state()==DocumentTemplateContract.State.PUBLISHED?"PASSED":"FAILED","TEMPLATE_STATE","当前模板状态="+exact.state(),exact.fileSha256());
            if(matches.size()==1){var p=matches.getFirst();if(p.documentType()==Type.FACT_RECORD&&exact.state()==DocumentTemplateContract.State.PUBLISHED)factTemplates++;
                boolean effective=!Instant.now().isBefore(p.effectiveFrom())&&(p.effectiveUntil()==null||Instant.now().isBefore(p.effectiveUntil()));
                add(checks,"生效时间",exact.templateId(),exact.revision(),effective?"PASSED":"FAILED","PROFILE_EFFECTIVITY","从="+p.effectiveFrom()+"；至="+p.effectiveUntil(),null);
            }else add(checks,"版本兼容关系",exact.templateId(),exact.revision(),"FAILED","EXACT_PROFILE_BINDING_MISSING","当前模板并非精确配置绑定的ENF14版本；不按名称猜测兼容",null);
        }
        if(factTemplates==0)add(checks,"模板版本/发布状态","",0,"FAILED","NO_PUBLISHED_FACT_BASELINE","当前没有可读且已发布、精确绑定事实记录配置的模板",null);
        var ruleRows=db.query("SELECT rule_id,revision FROM enf10_document_rule r WHERE organization_id=? AND revision=(SELECT MAX(revision) FROM enf10_document_rule x WHERE x.rule_id=r.rule_id)",(rs,n)->new Object[]{rs.getString(1),rs.getLong(2)},org);
        int compatible=0;
        for(var coordinate:ruleRows) {
            String id=(String)coordinate[0];long version=(Long)coordinate[1];if(!allowed(actor,org,"DOCUMENT_RULE",id,version,"DOCUMENT_MAPPING",checks))continue;
            var rule=rules.read(id,version).orElseThrow();boolean published=rule.state()==DocumentRuleContract.State.PUBLISHED;
            add(checks,"映射版本/规则版本",id,version,published?"PASSED":"FAILED","RULE_STATE","映射与规则是同一不可变版本；当前状态="+rule.state()+"；模板="+rule.templateId()+"@"+rule.templateRevision()+"；schema="+rule.schemaId()+"@"+rule.schemaVersion(),null);
            var template=templates.revision(rule.templateId(),rule.templateRevision());
            var profilesForRule=template.isEmpty()?List.<DocumentTemplateProfile>of():profiles.all().stream().filter(p->Objects.equals(runtime.profileBinding(p),template.get().approvalRef())&&p.documentType()==Type.FACT_RECORD).toList();
            if(profilesForRule.size()!=1){add(checks,"版本兼容关系",id,version,"FAILED","RULE_NOT_FACT_TEMPLATE","规则未绑定事实记录基线的精确模板版本",null);continue;}
            compatible++;
            sourceCheck(actor,org,rule,materialRefs,checks);
            if(!published||caseRef==null||materialRefs.isEmpty())continue;
            var candidate=new Input(org,Type.FACT_RECORD,caseRef,new ObjectVersion(rule.templateId(),rule.templateRevision(),rule.templateSha256()),new ObjectVersion(id,version,sha(encode(rule))),materialRefs,legalRefs,null,null);
            try {var resolved=runtime.resolve(actor,candidate);var content=new DocumentProfileEngine().compose(resolved.profile(),Instant.now(),resolved.region(),resolved.stage(),rule,resolved.sources(),resolved.mapping());
                boolean dependencyFailure=content.issues().stream().anyMatch(d->d.blocking()&&!Set.of("MISSING_SOURCE_VALUE","PROTECTED_INPUT_UNCONFIRMED").contains(d.code()))
                    ||resolved.mapping().diagnostics().stream().anyMatch(d->d.blocking()&&!Set.of("MISSING_SOURCE_VALUE","UNCONFIRMED_PROTECTED_VALUE").contains(d.code()));
                for(var diagnostic:resolved.mapping().diagnostics())add(checks,"字段映射",id,version,diagnostic.blocking()?"FAILED":"PENDING",diagnostic.code(),"字段="+diagnostic.fieldId()+"；来源路径="+diagnostic.sourcePath(),null);
                for(var issue:content.issues())add(checks,"输入对象状态",id,version,issue.blocking()?"FAILED":"PENDING",issue.code(),"字段/章节="+issue.field(),null);
                if(dependencyFailure)continue;choices.add(candidate);add(checks,"可消费集合",id,version,"PASSED","CONSUMABLE","精确运行对象关系通过；来源缺项仍在prepare展示，不补造值",null);
            }catch(SecurityException denied){add(checks,"当前授权",id,version,"FAILED","CURRENT_AUTHORIZATION_UNAVAILABLE","模板/schema/来源/材料/引用的现时授权未通过",null);}
            catch(RuntimeException failure){add(checks,"版本兼容关系",id,version,"FAILED","INPUT_RESOLUTION_FAILED","运行输入核验失败："+safeReason(failure),null);}
        }
        if(compatible==0)add(checks,"映射版本/规则版本","",0,"FAILED","NO_FACT_BASELINE_MAPPING","没有可读的映射规则绑定到已发布事实记录基线；现有其他模板规则不能按名称复用",null);
        return report(org,choices,checks);
    }
    private void sourceCheck(String actor,String org,DocumentRuleContract.Rule rule,List<ObjectVersion> evidenceRefs,List<Check> checks) {
        try {var catalog=json.readValue(Files.readAllBytes(catalogFile),FileDocumentRuleInputs.Catalog.class);
            var snapshots=catalog.schemas().stream().filter(s->org.equals(s.organization())&&rule.schemaId().equals(s.schema().id())&&rule.schemaVersion().equals(s.schema().version())).toList();
            if(snapshots.size()!=1){add(checks,"案件来源/schema",rule.id(),rule.revision(),"FAILED","EXACT_SCHEMA_MISSING","配置中没有唯一同组织、同版schema",null);return;}
            var source=snapshots.getFirst();if(!allowed(actor,org,"DOCUMENT_SCHEMA",source.schema().id(),source.authorizationRevision(),"DOCUMENT_MAPPING",checks)||!allowed(actor,org,"DOCUMENT_MAPPING_SOURCE",source.sourceId(),source.sourceRevision(),"DOCUMENT_MAPPING",checks))return;
            boolean admitted=evidenceRefs.stream().anyMatch(v->v.sha256().equals(source.sourceSha256()));
            add(checks,"来源/材料同源关系",source.sourceId(),source.sourceRevision(),admitted?"PASSED":"FAILED","MAPPING_ORIGINAL_ADMISSION","映射输入原件SHA是否实际纳入该案件材料="+admitted,source.sourceSha256());
        }catch(Exception failure){add(checks,"案件来源/schema",rule.id(),rule.revision(),"FAILED","CATALOG_UNAVAILABLE","schema/来源配置不可读取",null);}
    }
    private void configuredSources(String actor,String org,ObjectVersion caseRef,List<ObjectVersion> evidenceRefs,List<Check> checks) {
        try {
            var catalog=json.readValue(Files.readAllBytes(catalogFile),FileDocumentRuleInputs.Catalog.class);int count=0;
            for(var snapshot:catalog.schemas())if(org.equals(snapshot.organization())) {
                if(!allowed(actor,org,"DOCUMENT_SCHEMA",snapshot.schema().id(),snapshot.authorizationRevision(),"DOCUMENT_MAPPING",checks)
                        ||!allowed(actor,org,"DOCUMENT_MAPPING_SOURCE",snapshot.sourceId(),snapshot.sourceRevision(),"DOCUMENT_MAPPING",checks))continue;
                count++;
                add(checks,"schema/来源版本",snapshot.sourceId(),snapshot.sourceRevision(),"PASSED","CONFIGURED_SOURCE_COORDINATE","schema="+snapshot.schema().id()+"@"+snapshot.schema().version()+"；字段数="+snapshot.schema().fields().size(),snapshot.sourceSha256());
                try {Path path=Path.of(snapshot.sourceFile());if(!path.isAbsolute())path=catalogFile.toAbsolutePath().getParent().resolve(path).normalize();
                    boolean intact=sha(Files.readAllBytes(path)).equals(snapshot.sourceSha256());add(checks,"来源原件",snapshot.sourceId(),snapshot.sourceRevision(),intact?"PASSED":"FAILED","SOURCE_ORIGINAL_SHA","配置的源原件是否与记录SHA一致="+intact,snapshot.sourceSha256());
                }catch(Exception failure){add(checks,"来源原件",snapshot.sourceId(),snapshot.sourceRevision(),"FAILED","SOURCE_ORIGINAL_UNAVAILABLE","配置的源原件不可读，不创建替代值",snapshot.sourceSha256());}
                boolean admitted=evidenceRefs.stream().anyMatch(v->v.sha256().equals(snapshot.sourceSha256()));
                add(checks,"来源/材料同源关系",snapshot.sourceId(),snapshot.sourceRevision(),caseRef==null?"PENDING":admitted?"PASSED":"FAILED","CONFIGURED_SOURCE_ADMISSION",caseRef==null?"案件读取未通过，无法核同源；不是已验证匹配":"配置源原件是否实际纳入当前案件材料="+admitted,snapshot.sourceSha256());
            }
            if(count==0)add(checks,"schema/来源版本","",0,"FAILED","NO_READABLE_SOURCE_SCHEMA","当前没有可读的同组织schema及来源原件坐标",null);
        }catch(Exception failure){add(checks,"schema/来源版本","",0,"FAILED","SOURCE_CATALOG_UNAVAILABLE","配置来源目录不可读",null);}
    }
    private boolean allowed(String actor,String org,String type,String id,long version,String purpose,List<Check> checks) {
        try{access.require(actor,org,type,id,version,"READ",purpose);add(checks,"当前授权",id,version,"PASSED",type+"_READ","当前组织、用途、资源和版本READ通过",null);return true;}
        catch(SecurityException denied){add(checks,"当前授权","未授权对象",0,"FAILED",type+"_READ_UNAVAILABLE","未通过当前读取授权；不披露资源标识、版本或原件",null);return false;}
    }
    private static Report report(String org,List<Input> inputs,List<Check> checks){return new Report(org,Type.FACT_RECORD,Instant.now(),inputs,checks);}
    private static void add(List<Check> rows,String category,String id,long revision,String state,String code,String reason,String sha){rows.add(new Check(category,id,revision,state,code,reason,sha));}
    private byte[] encode(Object value){try{return json.writeValueAsBytes(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeReason(RuntimeException failure){if(failure instanceof IllegalArgumentException&&failure.getMessage()!=null)return failure.getMessage();return "原件、状态或依赖不可用，未生成替代结果";}
    private static String sha(byte[] raw){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException(e);}}
}
