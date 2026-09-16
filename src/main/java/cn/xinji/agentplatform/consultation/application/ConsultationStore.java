package cn.xinji.agentplatform.consultation.application;

import java.util.Optional;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;

public interface ConsultationStore {
    Optional<StoredResult> findByIdempotency(String requester, String key);
    Optional<StoredResult> findByConsultationId(String consultationId);
    Optional<StoredResult> findByAnswerId(String consultationId, String answerId);
    void save(String requester, String key, String question, String purpose, ConsultationOutcome outcome);
    void saveSupplement(String requester, String key, String content, String accumulatedInput, String purpose, ConsultationOutcome outcome);
    void saveRefresh(String requester, ConsultationOutcome outcome);

    record StoredResult(String requester, String fingerprint, String accumulatedInput, ConsultationOutcome outcome) {}
}
