package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.Policy;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Released policy bytes stamp every new report. Configuration cannot remove evidence or authorization guards. */
public final class CaseReportPolicy {
    public static final Set<String> MANDATORY=Set.of("CASE_CONTEXT","PARTY_MATERIALS","EXACT_ORIGINAL","CONFIRMED_CLAIM","ELEMENT_CORRESPONDENCE","CONFLICT_RESOLUTION","UNCERTAINTY_RESOLUTION","LEGAL_CITATION","INDEPENDENT_REVIEW","EXACT_REPORT_VERSION");
    public record Configuration(String version,String method,String accountableRole,List<String> sourceReferences,List<String> mandatoryRules,List<String> sectionOrder,int maxTextLength,boolean opposingRequiresResolution){
        public Configuration{
            if(!"ADJ_CASE_REPORT/v2".equals(version)||method==null||method.isBlank()||accountableRole==null||accountableRole.isBlank()||sourceReferences==null||sourceReferences.isEmpty()||sourceReferences.stream().anyMatch(String::isBlank)||mandatoryRules==null||!Set.copyOf(mandatoryRules).equals(MANDATORY)||mandatoryRules.size()!=MANDATORY.size())throw new IllegalArgumentException("INVALID_CASE_REPORT_CONFIGURATION");
            sourceReferences=List.copyOf(sourceReferences);mandatoryRules=List.copyOf(mandatoryRules);sectionOrder=List.copyOf(sectionOrder);
            new Policy(version,sectionOrder,maxTextLength,opposingRequiresResolution);
        }
    }
    public static Policy load(CaseWorkCodec codec,String file,int maxBudget)throws IOException{
        byte[] bytes;
        if(file==null||file.isBlank()){try(var in=CaseReportPolicy.class.getResourceAsStream("/adjudication/report-policy-v2.json")){if(in==null)throw new IOException("Released adjudication policy missing");bytes=in.readNBytes(262145);}}
        else{Path path=Path.of(file).toAbsolutePath().normalize();if(!Files.isRegularFile(path)||Files.size(path)>262144)throw new IOException("Invalid adjudication policy file");bytes=Files.readAllBytes(path);}
        if(bytes.length>262144)throw new IOException("Adjudication policy budget exceeded");
        var config=codec.decode(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),Configuration.class);
        int max=Math.min(config.maxTextLength(),maxBudget);if(max<1)throw new IllegalArgumentException("INVALID_REPORT_POLICY");
        var stamp=sha256(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)+"\ntransportBudget="+maxBudget);
        return new Policy(config.version()+":"+stamp,config.sectionOrder(),max,config.opposingRequiresResolution());
    }
}
