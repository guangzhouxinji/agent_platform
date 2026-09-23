package cn.xinji.agentplatform.consultation.application;

import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.List;

public interface ConsultationAnswerModelPort {
    AnswerDraft generate(String modelId, String question, List<ConsultationCandidate> candidates, String traceId);

    record AnswerDraft(String content, List<String> citedSourceIds) {}

    final class InvocationFailure extends RuntimeException {
        private final String code;

        public InvocationFailure(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }
}
