package cn.xinji.agentplatform.consultation.adapter.out.memory;

import cn.xinji.agentplatform.consultation.application.ConsultationStore;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryConsultationStore implements ConsultationStore {
    private final Map<String, StoredResult> idempotency = new HashMap<>();
    private final Map<String, StoredResult> consultations = new HashMap<>();
    private final Map<String, StoredResult> answers = new HashMap<>();
    @Override public Optional<StoredResult> findByIdempotency(String requester, String key) { return Optional.ofNullable(idempotency.get(requester + "|" + key)); }
    @Override public Optional<StoredResult> findByConsultationId(String consultationId) { return Optional.ofNullable(consultations.get(consultationId)); }
    @Override public Optional<StoredResult> findByAnswerId(String consultationId, String answerId) { return Optional.ofNullable(answers.get(consultationId + "|" + answerId)); }
    @Override public void save(String requester, String key, String question, String purpose, ConsultationOutcome outcome) {
        store(requester, key, question, outcome);
    }
    @Override public void saveSupplement(String requester, String key, String content, String accumulatedInput, String purpose, ConsultationOutcome outcome) {
        store(requester, key, accumulatedInput, outcome);
    }
    @Override public void saveRefresh(String requester, ConsultationOutcome outcome) {
        String accumulatedInput = Optional.ofNullable(consultations.get(outcome.consultationId())).map(StoredResult::accumulatedInput).orElse("");
        StoredResult stored=new StoredResult(requester, outcome.fingerprint(), accumulatedInput, outcome);
        consultations.put(outcome.consultationId(), stored); answers.put(outcome.consultationId()+"|"+outcome.answerId(), stored);
    }
    private void store(String requester, String key, String accumulatedInput, ConsultationOutcome outcome) {
        StoredResult stored = new StoredResult(requester, outcome.fingerprint(), accumulatedInput, outcome);
        idempotency.put(requester + "|" + key, stored); consultations.put(outcome.consultationId(), stored); answers.put(outcome.consultationId()+"|"+outcome.answerId(), stored);
    }
}
