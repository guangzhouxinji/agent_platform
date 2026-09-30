package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.*;

public record PatentHumanState(Map<String, PatentHumanAction.Handoff> prepared,
                               Map<String, PatentHumanAction.Receipt> receipts) {
    public PatentHumanState { prepared = Map.copyOf(prepared); receipts = Map.copyOf(receipts); }
    public static PatentHumanState empty() { return new PatentHumanState(Map.of(), Map.of()); }

    public PatentHumanState apply(PatentHumanAction action, PatentTaskSnapshot current,
                                  ReceiptAuthority verifier) {
        Map<String, PatentHumanAction.Handoff> packages = new LinkedHashMap<>(prepared);
        Map<String, PatentHumanAction.Receipt> responses = new LinkedHashMap<>(receipts);
        if (action.kind() == PatentHumanAction.Kind.PREPARE) {
            var packet = action.handoff();
            if (current == null || packet.taskVersion() != current.version()
                    || current.subTasks().stream().noneMatch(item -> item.subTaskId().equals(packet.subTaskId()))
                    || packages.containsKey(packet.key())) {
                throw new IllegalArgumentException("handoff must refer to the current task and a new version");
            }
            packages.put(packet.key(), packet);
        } else {
            var receipt = action.receipt();
            var packet = packages.get(receipt.handoffKey());
            if (packet == null || !packet.scopeReference().equals(receipt.scopeReference())
                    || !verifier.isAuthoritative(receipt)) {
                throw new IllegalArgumentException("unverified or mismatched human receipt");
            }
            var prior = responses.get(receipt.authoritativeId());
            if (prior != null && !prior.equals(receipt)) {
                throw new IllegalArgumentException("conflicting authoritative receipt");
            }
            if (prior != null) throw new IllegalArgumentException("duplicate receipt requires command replay");
            responses.put(receipt.authoritativeId(), receipt);
        }
        return new PatentHumanState(packages, responses);
    }

    @FunctionalInterface
    public interface ReceiptAuthority {
        boolean isAuthoritative(PatentHumanAction.Receipt receipt);
    }
}
