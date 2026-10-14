package cn.xinji.agentplatform.security.product;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix;
import java.time.Instant;
import java.util.List;
final class ProductComparisonMatrixResults {
    record Package(String taskId,String comparisonId,String candidateId,String inputSha,String attemptId,long token,
            String reportId,long resultVersion,String methodVersion,String workerSubject,String configurationVersion,String approvalSha,
            String responseSha,Instant createdAt,Instant expiresAt,List<PatentComparisonMatrix.Row> rows) { }
    record Receipt(String taskId,String comparisonId,String candidateId,String inputSha,String attemptId,long token,
                   String reportId,String reportSha,long resultVersion,String status) { }
    record Report(Package result,String reportSha,Receipt receipt) { }
}
