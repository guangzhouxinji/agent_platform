package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.evidence-read-enabled", havingValue = "true")
final class DevEvidenceReadController {
    private final DevTextResultStore store;
    private final DevTaskCoreController core;
    private final SyntheticUsePolicy uses;

    DevEvidenceReadController(DevTextResultStore store, DevTaskCoreController core, SyntheticUsePolicy uses) {
        this.store = store;
        this.core = core;
        this.uses = uses;
    }

    @GetMapping("/v2/consultation/tasks/{id}/text-searches/{setId}/members/{ordinal}/evidence-preparation")
    DevTextResultStore.EvidencePreparation read(HttpServletRequest request, @PathVariable String id,
                                                 @PathVariable String setId, @PathVariable int ordinal) {
        var actor = core.actor(request);
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return store.prepareEvidence(actor, id, setId, ordinal, core.guard(request),
                (human, record, version, digest, query, save, display, now) ->
                        subject != null && human.principal().equals(subject.stableSubjectId())
                                && human.organization().equals(subject.organizationRef())
                                ? uses.claim(subject, record, version, digest, query, save, display, now) : null);
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
