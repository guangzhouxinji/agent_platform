package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentTemplateProfile.*;
import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentProfileEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Component-only source examples; not a case, operator, approval, or real product-I result. */
class DocumentProfileEngineTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final DocumentProfileCatalog catalog=DocumentProfileCatalog.builtin(json);
    final Instant now=Instant.parse("2026-10-02T04:00:00Z");
    SourceDatum source(Object value){return new SourceDatum(value,"COMPONENT_INPUT",1,"a".repeat(64),"component locator",true);}
    Map<String,SourceDatum> complete(DocumentTemplateProfile p) {
        var result=new HashMap<String,SourceDatum>();
        for(var f:p.fields()) {
            Object value=switch(f.type()){case "DATE"->"2026-09-29";case "DECIMAL"->BigDecimal.ZERO;default->"component field value";};
            String path=p.defaultMappings().stream().filter(m->m.fieldId().equals(f.id())).findFirst().orElseThrow().sourcePath();
            result.put(path,source(f.repeated()?List.of(value):value));
        }
        return result;
    }
    DocumentRuleContract.Rule rule(DocumentTemplateProfile p) {
        return new DocumentRuleContract.Rule("COMPONENT_RULE",1,"COMPONENT_ORG",DocumentRuleContract.State.PUBLISHED,"COMPONENT_TEMPLATE",1,"a".repeat(64),"COMPONENT_SCHEMA","1",p.defaultMappings());
    }
    Content compose(DocumentTemplateProfile p,Instant at,String region,String stage,DocumentRuleContract.Rule rule,Map<String,SourceDatum> sources) {
        var inputs=new DocumentRuleContract.Inputs() {
            public DocumentRuleContract.Template template(String actor,String org,String id,long version) {
                return new DocumentRuleContract.Template(id,version,rule.templateSha256(),p.fields().stream()
                    .map(f->new DocumentRuleContract.Field(f.id(),f.type(),f.requiredForReview(),f.repeated())).toList());
            }
            public DocumentRuleContract.Schema schema(String actor,String org,String id,String version) {
                return new DocumentRuleContract.Schema(id,version,rule.mappings().stream().map(m->{
                    var f=p.fields().stream().filter(d->d.id().equals(m.fieldId())).findFirst().orElseThrow();
                    return new DocumentRuleContract.SchemaField(m.sourcePath(),f.type(),"COMPONENT_INPUT",true);
                }).toList());
            }
            public Map<String,DocumentRuleContract.Value> values(String actor,String org,DocumentRuleContract.Schema schema,String purpose) {
                var values=new HashMap<String,DocumentRuleContract.Value>();sources.forEach((path,s)->values.put(path,
                    new DocumentRuleContract.Value(s.value(),s.sourceRef(),Long.toString(s.sourceVersion()),s.locator(),s.confirmed())));return values;
            }
            public void assertCurrent(String actor,String org,DocumentRuleContract.Rule snapshot,String purpose) { }
        };
        var trial=new cn.xinji.agentplatform.enforcement.documentrule.DocumentMappingValidator(inputs).trial("COMPONENT_ACTOR",rule,"COMPONENT_TEST");
        return new DocumentProfileEngine().compose(p,at,region,stage,rule,sources,trial);
    }
    @Test void threeTypesAreNonOfficialVersionedProductResources() {
        assertEquals(3,catalog.all().size());assertEquals(3,catalog.all().stream().map(DocumentTemplateProfile::documentType).distinct().count());
        for(var profile:catalog.all()){assertEquals("NON_OFFICIAL",profile.classification());assertEquals(1,profile.revision());assertFalse(profile.fields().isEmpty());}
    }
    @Test void allThreeTypesUseSameEngineAndRetainSourceCoordinates() {
        for(var profile:catalog.all()) {
            var content=compose(profile,now,null,null,rule(profile),complete(profile));
            assertFalse(content.reviewBlocked());assertEquals(profile,content.profile());
            assertTrue(content.sections().stream().flatMap(s->s.fields().stream()).allMatch(f->f.source()!=null&&f.source().sourceVersion()==1));
        }
    }
    @Test void requiredMissingIsVisibleWithoutInventedValue() {
        var p=catalog.all().getFirst();var input=complete(p);input.remove("party.name");
        var content=compose(p,now,null,null,rule(p),input);
        assertTrue(content.reviewBlocked());var field=content.sections().stream().flatMap(s->s.fields().stream()).filter(f->f.definition().id().equals("party_name")).findFirst().orElseThrow();
        assertTrue(field.missing());assertNull(field.value());assertNull(field.source());
    }
    @Test void absentConditionIsUnknownWhileConfirmedEmptyIsHidden() {
        var p=catalog.all().getFirst();var input=complete(p);input.remove("review.comment");var engine=new DocumentProfileEngine();
        var unknown=compose(p,now,null,null,rule(p),input);
        assertTrue(unknown.reviewRequired());assertEquals(Visibility.UNKNOWN,unknown.sections().getLast().visibility());
        input.put("review.comment",source(null));assertEquals(Visibility.HIDDEN,compose(p,now,null,null,rule(p),input).sections().getLast().visibility());
    }
    @Test void effectiveTimeHasInclusiveStartAndExclusiveEnd() {
        var p=catalog.all().getFirst();var ending=new DocumentTemplateProfile(p.id(),2,p.classification(),p.documentType(),p.title(),p.effectiveFrom(),now,p.regions(),p.stages(),p.fields(),p.defaultMappings(),p.sections());
        assertTrue(compose(ending,now,null,null,rule(ending),complete(p)).reviewBlocked());
        assertFalse(compose(p,p.effectiveFrom(),null,null,rule(p),complete(p)).reviewBlocked());
    }
    @Test void fieldsCanChangeInConfigurationWithoutEngineCodeAndOldSnapshotStaysBound() {
        var p=catalog.all().getFirst();var fields=new ArrayList<>(p.fields());fields.set(0,new FieldDefinition("case_ref","新版关联标签","STRING",true,false,false));
        var next=new DocumentTemplateProfile(p.id(),2,p.classification(),p.documentType(),"新版配置标题",p.effectiveFrom(),null,p.regions(),p.stages(),fields,p.defaultMappings(),p.sections());
        var old=compose(p,now,null,null,rule(p),complete(p));var newer=compose(next,now,null,null,rule(next),complete(next));
        assertEquals(1,old.profile().revision());assertEquals(2,newer.profile().revision());assertNotEquals(old.profile().title(),newer.profile().title());
    }
    @Test void sourceIdentityAndAmountCannotBeFreelyEditableOrUnconfirmed() {
        assertThrows(IllegalArgumentException.class,()->new FieldDefinition("amount","金额","DECIMAL",true,false,true));
        var p=catalog.all().getLast();var input=complete(p);input.put("penalty.options[].amount",new SourceDatum(List.of("0.00"),"COMPONENT_INPUT",1,"a".repeat(64),"component locator",false));
        assertTrue(compose(p,now,null,null,rule(p),input).reviewBlocked());assertThrows(IllegalArgumentException.class,()->source(0.1d));
    }
    @Test void sourceContainersAreImmutableAndZeroIsNotMissing() {
        var mutable=new ArrayList<Object>();mutable.add("0.00");var datum=source(mutable);mutable.clear();assertEquals(List.of("0.00"),datum.value());
        var p=catalog.all().getLast();var input=complete(p);var content=compose(p,now,null,null,rule(p),input);
        assertFalse(content.reviewBlocked());assertTrue(content.sections().stream().flatMap(s->s.fields().stream()).filter(f->f.definition().type().equals("DECIMAL")).allMatch(f->!f.missing()));
    }
    @Test void publishedRuleMappingCanChangeWithoutReplacingTemplateOrEngine() {
        var p=catalog.all().getFirst();var values=complete(p);values.put("party.alternateName",source("alternate component text"));
        var mappings=p.defaultMappings().stream().map(m->m.fieldId().equals("party_name")?
            new DocumentRuleContract.Mapping(m.fieldId(),"party.alternateName",m.schemaVersion(),m.mode(),m.transform(),m.missing()):m).toList();
        var published=new DocumentRuleContract.Rule("COMPONENT_RULE",2,"COMPONENT_ORG",DocumentRuleContract.State.PUBLISHED,"COMPONENT_TEMPLATE",1,"a".repeat(64),"COMPONENT_SCHEMA","1",mappings);
        var content=compose(p,now,null,null,published,values);
        assertEquals(1,content.profile().revision());assertEquals(2,content.rule().revision());
        assertEquals("alternate component text",content.sections().stream().flatMap(s->s.fields().stream()).filter(f->f.definition().id().equals("party_name")).findFirst().orElseThrow().value());
    }
    @Test void profileDefaultMappingsNeverBecomePublishedRulesImplicitly() {
        var p=catalog.all().getFirst();var draftRule=new DocumentRuleContract.Rule("COMPONENT_RULE",1,"COMPONENT_ORG",DocumentRuleContract.State.DRAFT,"COMPONENT_TEMPLATE",1,"a".repeat(64),"COMPONENT_SCHEMA","1",p.defaultMappings());
        assertThrows(IllegalArgumentException.class,()->compose(p,now,null,null,draftRule,complete(p)));
    }
    @Test void invalidFieldsAndUnknownVersionsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new FieldDefinition("bad-field","label","STRING",true,false,false));
        assertThrows(IllegalArgumentException.class,()->catalog.exact(catalog.all().getFirst().id(),99));
        assertThrows(IllegalArgumentException.class,()->new Condition(Operator.EQUALS,"path",null));
    }
    @Test void exactTrialValueIsRenderedWhileOriginalSourceRemainsUnmodified() {
        var p=catalog.all().getFirst();var values=complete(p);var original=rule(p);
        var mappings=original.mappings();
        var published=new DocumentRuleContract.Rule(original.id(),original.revision(),original.organization(),original.state(),original.templateId(),original.templateRevision(),original.templateSha256(),original.schemaId(),original.schemaVersion(),mappings);
        values.put("record.recordedDate",source(LocalDate.parse("2026-09-29")));
        var mapped=new DocumentRuleContract.Mapped("recorded_date","2026-09-29","COMPONENT_INPUT","1","component locator",DocumentRuleContract.Mode.DETERMINISTIC,false);
        var trial=new DocumentRuleContract.Trial(false,false,List.of(mapped),List.of());
        var content=new DocumentProfileEngine().compose(p,now,null,null,published,values,trial);
        var field=content.sections().stream().flatMap(s->s.fields().stream()).filter(f->f.definition().id().equals("recorded_date")).findFirst().orElseThrow();
        assertEquals("2026-09-29",field.value());assertEquals(LocalDate.parse("2026-09-29"),field.source().value());
        assertThrows(IllegalArgumentException.class,()->new DocumentProfileEngine().compose(p,now,null,null,published,values));
    }
    @Test void missingTrialFieldNeverFallsBackToRawSourceValue() {
        var p=catalog.all().getFirst();var content=new DocumentProfileEngine().compose(p,now,null,null,rule(p),complete(p),
            new DocumentRuleContract.Trial(false,false,List.of(),List.of()));
        assertTrue(content.reviewBlocked());
        assertTrue(content.sections().stream().flatMap(s->s.fields().stream()).allMatch(f->f.missing()&&f.value()==null));
        assertTrue(content.sections().stream().flatMap(s->s.fields().stream()).allMatch(f->f.source()!=null));
    }
    @Test void trialWithForeignSourceVersionModeLocatorOrDuplicateFieldFailsClosed() {
        var p=catalog.all().getFirst();var values=complete(p);var engine=new DocumentProfileEngine();
        var valid=new DocumentRuleContract.Mapped("party_name","mapped","COMPONENT_INPUT","1","component locator",DocumentRuleContract.Mode.DIRECT,false);
        var variants=List.of(
            new DocumentRuleContract.Mapped("party_name","mapped","OTHER_SOURCE","1","component locator",DocumentRuleContract.Mode.DIRECT,false),
            new DocumentRuleContract.Mapped("party_name","mapped","COMPONENT_INPUT","2","component locator",DocumentRuleContract.Mode.DIRECT,false),
            new DocumentRuleContract.Mapped("party_name","mapped","COMPONENT_INPUT","1","other locator",DocumentRuleContract.Mode.DIRECT,false),
            new DocumentRuleContract.Mapped("party_name","mapped","COMPONENT_INPUT","1","component locator",DocumentRuleContract.Mode.AUXILIARY_DRAFT,false),
            new DocumentRuleContract.Mapped("unknown_field","mapped","COMPONENT_INPUT","1","component locator",DocumentRuleContract.Mode.DIRECT,false));
        for(var variant:variants)assertThrows(IllegalArgumentException.class,()->engine.compose(p,now,null,null,rule(p),values,
            new DocumentRuleContract.Trial(true,false,List.of(variant),List.of())));
        assertThrows(IllegalArgumentException.class,()->engine.compose(p,now,null,null,rule(p),values,
            new DocumentRuleContract.Trial(true,false,List.of(valid,valid),List.of())));
    }
}
