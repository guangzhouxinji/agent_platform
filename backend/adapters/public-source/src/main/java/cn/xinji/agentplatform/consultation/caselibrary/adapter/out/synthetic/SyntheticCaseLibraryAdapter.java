package cn.xinji.agentplatform.consultation.caselibrary.adapter.out.synthetic;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseLibraryPort;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord.*;
import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.publicpilot.PublicPilotCaseLoader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("test-synthetic")
public final class SyntheticCaseLibraryAdapter implements CaseLibraryPort {
    private static final String VERSION = "synthetic-case-library-v1+public-pilot-v1";
    private final List<CaseLibraryRecord> records;
    public SyntheticCaseLibraryAdapter(PublicPilotCaseLoader publicPilot) {
        List<CaseLibraryRecord> combined = new ArrayList<>(catalog());
        combined.addAll(publicPilot.records());
        records = List.copyOf(combined);
    }
    @Override public List<CaseLibraryRecord> records() { return records; }
    @Override public Optional<CaseLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return VERSION; }

    private static List<CaseLibraryRecord> catalog() {
        String[] ipTypes = {"专利", "商标", "著作权"};
        String[] institutions = {"宁江市中级人民法院", "临川知识产权局", "东港市市场监督管理局", "海陵知识产权法庭"};
        String[] procedures = {"一审", "二审", "行政裁决", "行政复议"};
        List<CaseLibraryRecord> values = new ArrayList<>();
        for (int i=1;i<=90;i++) {
            String id=String.format("CASE-SYN-%03d",i); String ip=ipTypes[(i-1)%ipTypes.length]; String procedure=procedures[(i-1)%procedures.length];
            LocalDate decision=LocalDate.of(2021+i%5, i%12+1, Math.min(20,i%20+1)); String docVersion=i%12==0?"v2":"v1";
            String docState=i%17==0?"PARSE_FAILED":i%11==0?"PARTIAL":"COMPLETE";
            List<LocatedParagraph> paragraphs="PARSE_FAILED".equals(docState)?List.of():List.of(
                    new LocatedParagraph("基本事实","第2页第3段","P2-P3","当事人围绕涉案"+ip+"权利的使用范围发生争议。"),
                    new LocatedParagraph("争议焦点","第4页第1段","P4-P1","争议焦点为相关行为是否落入权利保护范围。"),
                    new LocatedParagraph("处理结论","第8页第2段","P8-P2","处理机构结合已核实材料作出本合成案例所示结论。"));
            DocumentVersion document=new DocumentVersion(docVersion,docState,i%17==0?"FAILED":"QUALITY_CHECKED",paragraphs,"synthetic://cases/"+id+"/documents/"+docVersion,i%17==0?"合成 OCR/解析失败，保留原文引用":i%11==0?"合成来源仅提供部分段落":null);
            List<Attachment> attachments=List.of(new Attachment(id+"-ATT-01","决定书原文.pdf",i%19==0?"PARSE_FAILED":"AVAILABLE","synthetic://cases/"+id+"/attachments/1",i%19==0?"附件解析失败":null),
                    new Attachment(id+"-ATT-02","证据目录.pdf",i%13==0?"SOURCE_NOT_PROVIDED":"AVAILABLE","synthetic://cases/"+id+"/attachments/2",i%13==0?"合成来源未提供附件":null));
            int pair=i%2==0?i-1:i+1; List<Relation> relations=List.of(new Relation("SAME_DISPUTE_DIFFERENT_PROCEEDING",String.format("CASE-SYN-%03d",pair),procedures[(pair-1)%procedures.length],"合成来源明确提供的程序关联"));
            String annotationState=i%5==0?"PUBLISHED":i%7==0?"NEEDS_REVIEW":"PENDING_REVIEW";
            AnnotationVersion annotation=new AnnotationVersion(id+"-ANN-01",1,annotationState,docVersion,
                    List.of(new LocatedClaim("涉案权利和行为主体已由原文记载","VERIFIED","第2页第3段")),
                    List.of(new LocatedClaim("相关行为是否落入保护范围","VERIFIED","第4页第1段")),
                    List.of(new LocatedClaim("本内容仅为合成案例的已发布要点展示","VERIFIED","第8页第2段")),
                    List.of(new LawReference("LAW-SYN-001","知识产权合成示例规范","第十条",i%10==0?"VERSION_UNVERIFIED":"VERSION_FIXED","第7页第2段","原文引用：示例规范第十条")),
                    annotationState.equals("PUBLISHED")?"v0-reviewer":null,annotationState.equals("PUBLISHED")?decision.plusDays(5)+"T10:00:00":null);
            values.add(new CaseLibraryRecord("SYNTHETIC_CASE_CATALOG",id,docVersion,"SYN-CASE-"+String.format("%05d",i),i%13==0?null:"（"+(2020+i%6)+"）知合字第"+i+"号",
                    ip+"权利争议"+procedure+"案例（样本"+i+"）",procedure.contains("审")?"民事案件":"行政案件",ip,institutions[(i-1)%institutions.length],procedure,
                    decision.minusMonths(2),decision,"合成摘录：处理结果仅供内部功能验证。",decision.plusDays(3)+"T09:00:00",decision.plusDays(4)+"T09:00:00",
                    "synthetic://cases/"+id,"SYNTHETIC_V0",List.of(document),attachments,relations,List.of(annotation)));
        }
        return List.copyOf(values);
    }
}
