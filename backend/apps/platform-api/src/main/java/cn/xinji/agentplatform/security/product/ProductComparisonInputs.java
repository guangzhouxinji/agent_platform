package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import java.time.Instant;
import java.util.List;

final class ProductComparisonInputs {
    private ProductComparisonInputs() { }
    record Create(long expectedTaskVersion,String featureRevisionId,long featureRevisionNumber,String packageSha,
                  String previousComparisonId,String methodVersion) { }
    record Candidate(String candidateId,long ordinal) { }
    record Envelope(String comparisonId,String taskId,long taskVersion,String previousComparisonId,
                    Create command,FeatureView confirmedFeatures,String preparationId,String materialSha,
                    String currentPreparationSnapshot,String actorIssuer,String actorSubject,String requestId,
                    String requestSha,Instant createdAt,Instant expiresAt,List<Candidate> candidates) { }
    record Progress(String candidateId,String status) { }
    record View(Envelope input,String inputSha,List<Progress> candidateProgress) { }
    record Receipt(String taskId,String comparisonId,String inputSha,long taskVersion,String featureRevisionId,long featureRevisionNumber,
                   String packageSha,String purpose,String methodVersion,Instant expiresAt,List<Progress> candidates) {
        static Receipt from(View view,ProductComparisonJson json) {
            var e=view.input();var snapshot=json.stored(e.currentPreparationSnapshot(),com.fasterxml.jackson.databind.JsonNode.class);
            return new Receipt(e.taskId(),e.comparisonId(),view.inputSha(),e.taskVersion(),e.command().featureRevisionId(),e.command().featureRevisionNumber(),
                    e.command().packageSha(),snapshot.path("command").path("purpose").asText(),e.command().methodVersion(),e.expiresAt(),view.candidateProgress());
        }
    }
}
