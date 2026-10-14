package cn.xinji.agentplatform.enforcement.penalty;

import java.util.List;

/** Carries the existing publication diagnostics without confusing a business rejection with a CAS conflict. */
public final class PenaltyPublishRejected extends IllegalArgumentException {
    private final List<String> gaps;
    public PenaltyPublishRejected(List<String> gaps) {
        super("Rule publication prerequisites not satisfied");this.gaps=List.copyOf(gaps);
    }
    public List<String> gaps(){return gaps;}
}
