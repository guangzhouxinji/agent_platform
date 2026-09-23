package cn.xinji.agentplatform.consultation.trademarkcomparison.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonCommand;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonCommand.*;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask.*;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TrademarkComparisonService {
    public static final String RULE = "SYNTHETIC_TRADEMARK_OBSERVATION_V1";
    private final TrademarkComparisonSourcePort source;
    private final TrademarkComparisonTaskRepository tasks;
    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;

    public TrademarkComparisonService(TrademarkComparisonSourcePort source, TrademarkComparisonTaskRepository tasks,
            ConsultationAuthorizationPort authorization, Clock clock) {
        this.source=source;this.tasks=tasks;this.authorization=authorization;this.clock=clock;
    }

    public Preparation prepare(String actor, Purpose purpose, DocumentRef target, List<DocumentRef> candidates,
            List<Dimension> dimensions, String requestId) {
        access(actor,requestId); requirePurpose(purpose,requestId); TrademarkDocument targetDocument=required(target,"TARGET_NOT_FOUND",requestId);
        List<Dimension> selected=dimensions==null||dimensions.isEmpty()?List.of(Dimension.TEXT,Dimension.PHONETIC):distinctDimensions(dimensions,requestId);
        List<CandidateAvailability> availability=normalizeCandidates(target,candidates,requestId).stream().map(reference->{
            Optional<TrademarkDocument> found=source.findTrademark(reference.sourceId());
            if(found.isEmpty())return new CandidateAvailability(reference,"UNAVAILABLE","DOCUMENT_NOT_FOUND");
            if(!found.get().version().equals(reference.version()))return new CandidateAvailability(reference,"UNAVAILABLE","VERSION_CONFLICT");
            return new CandidateAvailability(reference,"AVAILABLE",null);
        }).toList();
        return new Preparation(source.dataMode(),source.comparisonSourceVersion(),source.ruleVersion(),target,
                targetDocument.categories(),targetDocument.goodsServices(),selected,availability,source.limitations(),requestId);
    }

    public TrademarkComparisonTask create(String actor, String key, TrademarkComparisonCommand command, String requestId) {
        access(actor,requestId); if(blank(key))throw error("IDEMPOTENCY_KEY_REQUIRED","幂等键必填",HttpStatus.BAD_REQUEST,requestId);
        validate(command,requestId); String fingerprint=fingerprint(command);
        Optional<TrademarkComparisonTask> existing=tasks.findByIdempotency(actor,key);
        if(existing.isPresent())return replay(existing.get(),fingerprint,requestId);
        TrademarkDocument target=required(command.target(),"TARGET_NOT_FOUND",requestId);
        List<PairResult> pairs=normalizeCandidates(command.target(),command.candidates(),requestId).stream().map(candidate->compare(target,candidate,command.dimensions())).toList();
        String status=pairs.stream().allMatch(pair->pair.status().equals("COMPLETED"))?"SUCCEEDED":pairs.stream().anyMatch(pair->!pair.status().equals("FAILED"))?"PARTIAL":"FAILED";
        TrademarkComparisonTask task=new TrademarkComparisonTask("TC-"+UUID.randomUUID(),actor,key,fingerprint,command,status,
                source.dataMode(),source.comparisonSourceVersion(),source.ruleVersion(),"EVIDENCE_TABLE_ONLY","UNREVIEWED",pairs,source.limitations(),requestId,clock.instant());
        return replay(tasks.saveIfAbsent(task),fingerprint,requestId);
    }

    public TrademarkComparisonTask get(String actor,String taskId,String requestId){access(actor,requestId);TrademarkComparisonTask task=tasks.findById(taskId).orElseThrow(()->error("TASK_NOT_FOUND","比对任务不存在",HttpStatus.NOT_FOUND,requestId));if(!task.requester().equals(actor))throw error("ACCESS_DENIED","当前身份无权读取任务",HttpStatus.FORBIDDEN,requestId);return task;}
    public List<TrademarkComparisonTask> history(String actor,String requestId){access(actor,requestId);return tasks.findByRequester(actor);}

    private PairResult compare(TrademarkDocument target, DocumentRef reference, List<Dimension> dimensions) {
        Optional<TrademarkDocument> candidate=source.findTrademark(reference.sourceId());
        if(candidate.isEmpty())return failed(reference,"DOCUMENT_NOT_FOUND");
        if(!candidate.get().version().equals(reference.version()))return failed(reference,"VERSION_CONFLICT");
        List<DimensionResult> results=dimensions.stream().map(dimension->dimension(dimension,target,candidate.get())).toList();
        String status=results.stream().allMatch(result->result.state().equals("ANALYZED"))?"COMPLETED":"PARTIAL";
        List<String> missing=results.stream().filter(result->!result.state().equals("ANALYZED")).map(DimensionResult::reason).distinct().toList();
        return new PairResult(reference,status,results,missing,"仅提供逐维可观察证据；不形成商标近似、注册或审查结论。");
    }

    private static DimensionResult dimension(Dimension dimension,TrademarkDocument target,TrademarkDocument candidate){
        return switch(dimension){
            case TEXT -> text(target,candidate);
            case PHONETIC -> new DimensionResult(dimension,"ANALYZED",
                    target.phonetic().equals(candidate.phonetic())?"目录受控读音字符串相同":"目录受控读音字符串不同",
                    List.of("目标名称："+target.name(),"候选名称："+candidate.name(),"目标受控读音："+target.phonetic(),"候选受控读音："+candidate.phonetic()),
                    "仅核对目录已有受控读音，不处理多音字、方言或外文读法歧义");
            case SEMANTIC -> unavailable(dimension,"NOT_ANALYZED","MODEL_UNAVAILABLE","未配置含义识别模型，不从名称推测含义");
            case IMAGE -> unavailable(dimension,"MATERIAL_UNAVAILABLE","IMAGE_NOT_PROVIDED","当前来源未提供可访问原图");
            case OVERALL_VISUAL -> unavailable(dimension,"MATERIAL_UNAVAILABLE","IMAGE_NOT_PROVIDED","缺少目标与候选原图，不能形成整体视觉意见");
            case GOODS_SERVICES -> new DimensionResult(dimension,"NOT_ANALYZED","仅展示确认范围，不判断类似关系",
                    List.of("目标类别："+String.join("、",target.categories()),"目标项目："+String.join("、",target.goodsServices()),
                            "候选类别："+String.join("、",candidate.categories()),"候选项目："+String.join("、",candidate.goodsServices())),
                    "STANDARD_UNAVAILABLE：未配置分类版本、类似群和商品服务关系标准");
        };
    }

    private static DimensionResult text(TrademarkDocument target,TrademarkDocument candidate){
        String left=target.name(),right=candidate.name();int samePosition=0;int limit=Math.min(left.length(),right.length());
        for(int index=0;index<limit;index++)if(left.charAt(index)==right.charAt(index))samePosition++;
        StringBuilder common=new StringBuilder();left.codePoints().distinct().forEach(code->{String value=new String(Character.toChars(code));if(!value.isBlank()&&right.contains(value))common.append(value);});
        String observation="目标 "+left.length()+" 字，候选 "+right.length()+" 字；相同位置字符 "+samePosition+" 个；共同文字："+(common.isEmpty()?"无":common);
        return new DimensionResult(Dimension.TEXT,"ANALYZED",observation,List.of("目标名称："+left,"候选名称："+right),"仅记录字形、顺序和组成的可观察差异");
    }
    private static DimensionResult unavailable(Dimension dimension,String state,String code,String explanation){return new DimensionResult(dimension,state,explanation,List.of(),code+"："+explanation);}
    private static PairResult failed(DocumentRef reference,String reason){return new PairResult(reference,"FAILED",List.of(),List.of(reason),"该候选未执行，不影响其他候选结果。");}

    private void validate(TrademarkComparisonCommand command,String requestId){
        if(command==null||command.target()==null)throw error("TARGET_REQUIRED","目标商标必填",HttpStatus.BAD_REQUEST,requestId);
        requirePurpose(command.purpose(),requestId);normalizeCandidates(command.target(),command.candidates(),requestId);
        requireValues(command.confirmedCategories(),"CATEGORIES_REQUIRED","至少确认一个类别",requestId);
        requireValues(command.confirmedGoodsServices(),"GOODS_SERVICES_REQUIRED","至少确认一个商品或服务项目",requestId);
        if(command.dimensions().isEmpty())throw error("DIMENSIONS_REQUIRED","至少选择一个分析维度",HttpStatus.BAD_REQUEST,requestId);
        distinctDimensions(command.dimensions(),requestId);
    }
    private TrademarkDocument required(DocumentRef reference,String code,String requestId){if(reference==null||blank(reference.sourceId())||blank(reference.version()))throw error("DOCUMENT_REFERENCE_REQUIRED","商标 ID 与版本必填",HttpStatus.BAD_REQUEST,requestId);TrademarkDocument document=source.findTrademark(reference.sourceId()).orElseThrow(()->error(code,"商标记录不存在",HttpStatus.NOT_FOUND,requestId));if(!document.version().equals(reference.version()))throw error("VERSION_CONFLICT","商标版本不一致",HttpStatus.CONFLICT,requestId);return document;}
    private static List<DocumentRef> normalizeCandidates(DocumentRef target,List<DocumentRef> candidates,String requestId){if(candidates==null||candidates.isEmpty())throw error("CANDIDATE_REQUIRED","至少选择一个候选商标",HttpStatus.BAD_REQUEST,requestId);Set<String> keys=new HashSet<>();for(DocumentRef reference:candidates){if(reference==null||blank(reference.sourceId())||blank(reference.version()))throw error("DOCUMENT_REFERENCE_REQUIRED","候选商标 ID 与版本必填",HttpStatus.BAD_REQUEST,requestId);String key=reference.sourceId()+"|"+reference.version();if(!keys.add(key))throw error("DUPLICATE_CANDIDATE","候选商标不得重复",HttpStatus.BAD_REQUEST,requestId);if(target!=null&&reference.equals(target))throw error("TARGET_AS_CANDIDATE","目标商标不能同时作为候选",HttpStatus.BAD_REQUEST,requestId);}return List.copyOf(candidates);}
    private static List<Dimension> distinctDimensions(List<Dimension> values,String requestId){if(values.stream().anyMatch(Objects::isNull)||new HashSet<>(values).size()!=values.size())throw error("INVALID_DIMENSIONS","分析维度必须完整且不重复",HttpStatus.BAD_REQUEST,requestId);return List.copyOf(values);}
    private static void requireValues(List<String> values,String code,String message,String requestId){if(values==null||values.isEmpty()||values.stream().anyMatch(TrademarkComparisonService::blank))throw error(code,message,HttpStatus.BAD_REQUEST,requestId);}
    private static void requirePurpose(Purpose purpose,String requestId){if(purpose==null)throw error("PURPOSE_REQUIRED","分析目的必填",HttpStatus.BAD_REQUEST,requestId);}
    private void access(String actor,String requestId){if(!authorization.maySearch(actor))throw error("ACCESS_DENIED","当前身份无权使用商标比对",HttpStatus.FORBIDDEN,requestId);}
    private static TrademarkComparisonTask replay(TrademarkComparisonTask task,String fingerprint,String requestId){if(!task.requestFingerprint().equals(fingerprint))throw error("IDEMPOTENCY_CONFLICT","同一幂等键的输入不同",HttpStatus.CONFLICT,requestId);return task;}
    private static String fingerprint(TrademarkComparisonCommand command){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(command.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ComparisonException error(String code,String message,HttpStatus status,String requestId){return new ComparisonException(code,message,status,requestId);}

    public record Preparation(String dataMode,String sourceVersion,String ruleVersion,DocumentRef target,List<String> suggestedCategories,
            List<String> suggestedGoodsServices,List<Dimension> selectedDimensions,List<CandidateAvailability> candidates,List<String> limitations,String requestId){}
    public record CandidateAvailability(DocumentRef candidate,String status,String reason){}
    public static final class ComparisonException extends RuntimeException{
        private final String code,requestId;private final HttpStatus status;
        ComparisonException(String code,String message,HttpStatus status,String requestId){super(message);this.code=code;this.status=status;this.requestId=requestId;}
        public String code(){return code;}public String requestId(){return requestId;}public HttpStatus status(){return status;}
    }
}
