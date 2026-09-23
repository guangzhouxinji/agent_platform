package cn.xinji.agentplatform.consultation.caselibrary.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CaseLibraryService {
    public static final String MODE="DEVELOPMENT_MIXED";
    public static final String LIMITATION="开发期合成案例与公开裁判测试样本；不代表真实中心案例、司法行政结论、专业标注或业务验收。";
    private final CaseLibraryPort source; private final ConsultationAuthorizationPort authorization; private final Clock clock; private final CaseAnnotationWorkflowService workflow;
    public CaseLibraryService(CaseLibraryPort source, ConsultationAuthorizationPort authorization, Clock clock,CaseAnnotationWorkflowService workflow){this.source=source;this.authorization=authorization;this.clock=clock;this.workflow=workflow;}
    public Page query(String actor, Query q, String requestId){
        access(actor,requestId); validate(q,requestId);
        List<CaseLibraryRecord> matches=source.records().stream()
                .filter(v->exact(v.caseNumber(),q.caseNumber())).filter(v->exact(v.docketNumber(),q.docketNumber())).filter(v->contains(v.title(),q.title()))
                .filter(v->q.caseTypes().isEmpty()||q.caseTypes().contains(v.caseType())).filter(v->q.ipTypes().isEmpty()||q.ipTypes().contains(v.intellectualPropertyType()))
                .filter(v->q.institutions().isEmpty()||q.institutions().contains(v.institution())).filter(v->q.procedures().isEmpty()||q.procedures().contains(v.procedure()))
                .filter(v->q.annotationStates().isEmpty()||v.annotations().stream().anyMatch(a->q.annotationStates().contains(a.state())))
                .filter(v->range(v.decisionDate(),q.decisionFrom(),q.decisionTo()))
                .sorted(Comparator.comparing(CaseLibraryRecord::decisionDate,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(CaseLibraryRecord::sourceRecordId)).toList();
        int from=Math.min((q.page()-1)*q.pageSize(),matches.size()),to=Math.min(from+q.pageSize(),matches.size());
        return new Page(source.dataMode(),source.sourceVersion(),limitation(),clock.instant().toString(),requestId,q.page(),q.pageSize(),matches.size(),List.copyOf(matches.subList(from,to)));
    }
    public Detail detail(String actor,String id,String requestId){access(actor,requestId);return new Detail(source.dataMode(),source.sourceVersion(),limitation(),clock.instant().toString(),requestId,find(id,requestId));}
    public Document document(String actor,String id,String version,String requestId){access(actor,requestId);CaseLibraryRecord r=find(id,requestId);DocumentVersion d=r.documents().stream().filter(v->v.versionId().equals(version)).findFirst().orElseThrow(()->error("DOCUMENT_NOT_FOUND","原文版本不存在",HttpStatus.NOT_FOUND,requestId));return new Document(source.dataMode(),source.sourceVersion(),limitation(),clock.instant().toString(),requestId,id,d);}
    public Annotation annotation(String actor,String id,String requestId){access(actor,requestId);CaseLibraryRecord r=find(id,requestId);AnnotationVersion value=workflow.published(id,r.sourceVersion()).map(a->new AnnotationVersion(a.annotationId(),a.version(),a.state(),a.sourceVersion(),List.of(new LocatedClaim(a.fact(),"VERIFIED",a.sourceLocation())),List.of(new LocatedClaim(a.issue(),"VERIFIED",a.sourceLocation())),List.of(new LocatedClaim(a.holding(),"VERIFIED",a.sourceLocation())),List.of(),a.actor(),a.updatedAt())).orElseGet(()->r.annotations().stream().filter(a->a.state().equals("PUBLISHED")&&a.sourceVersion().equals(r.sourceVersion())).findFirst().orElse(null));String state=value!=null?"PUBLISHED_AVAILABLE":r.annotations().stream().anyMatch(a->a.state().equals("NEEDS_REVIEW"))?"NEEDS_REVIEW":"NO_PUBLISHED_ANNOTATION";return new Annotation(source.dataMode(),source.sourceVersion(),limitation(),clock.instant().toString(),requestId,new AnnotationResult(state,value,value==null?"草稿、待审核、退回或待复核标注不作为当前已校核输出":null));}
    private CaseLibraryRecord find(String id,String requestId){return source.find(id).orElseThrow(()->error("RECORD_NOT_FOUND","案例记录不存在",HttpStatus.NOT_FOUND,requestId));}
    private String limitation(){return "PUBLIC_LOCAL".equals(source.dataMode())?"本地保存的公开知识产权裁判开发样本；字段和标注可能不完整，不代表权威案例库、司法行政结论或业务验收。":LIMITATION;}
    private void access(String actor,String requestId){if(!authorization.maySearch(actor))throw error("ACCESS_DENIED","当前身份无权查询案例库",HttpStatus.FORBIDDEN,requestId);}
    private static void validate(Query q,String id){if(q.page()<1||!Set.of(10,20,50).contains(q.pageSize()))throw error("INVALID_REQUEST","分页参数无效",HttpStatus.BAD_REQUEST,id);if(q.decisionFrom()!=null&&q.decisionTo()!=null&&q.decisionFrom().isAfter(q.decisionTo()))throw error("INVALID_REQUEST","决定日期起始日期不能晚于结束日期",HttpStatus.BAD_REQUEST,id);}
    private static boolean exact(String v,String q){return blank(q)||(v!=null&&v.equals(q.trim()));} private static boolean contains(String v,String q){return blank(q)||(v!=null&&v.contains(q.trim()));} private static boolean blank(String v){return v==null||v.isBlank();}
    private static boolean range(LocalDate v,LocalDate f,LocalDate t){return f==null&&t==null||v!=null&&(f==null||!v.isBefore(f))&&(t==null||!v.isAfter(t));}
    private static LibraryException error(String c,String m,HttpStatus s,String id){return new LibraryException(c,m,s,id);}
    public record Query(String caseNumber,String docketNumber,String title,List<String> caseTypes,List<String> ipTypes,List<String> institutions,List<String> procedures,List<String> annotationStates,LocalDate decisionFrom,LocalDate decisionTo,int page,int pageSize){public Query{caseTypes=copy(caseTypes);ipTypes=copy(ipTypes);institutions=copy(institutions);procedures=copy(procedures);annotationStates=copy(annotationStates);}private static List<String> copy(List<String> v){return v==null?List.of():List.copyOf(v);}}
    public record Page(String dataMode,String sourceVersion,String limitation,String fetchedAt,String requestId,int page,int pageSize,int total,List<CaseLibraryRecord> items){}
    public record Detail(String dataMode,String sourceVersion,String limitation,String fetchedAt,String requestId,CaseLibraryRecord item){}
    public record Document(String dataMode,String sourceVersion,String limitation,String fetchedAt,String requestId,String sourceRecordId,DocumentVersion document){}
    public record Annotation(String dataMode,String sourceVersion,String limitation,String fetchedAt,String requestId,AnnotationResult annotation){}
    public static final class LibraryException extends RuntimeException{private final String code;private final HttpStatus status;private final String requestId;public LibraryException(String c,String m,HttpStatus s,String r){super(m);code=c;status=s;requestId=r;}public String code(){return code;}public HttpStatus status(){return status;}public String requestId(){return requestId;}}
}
