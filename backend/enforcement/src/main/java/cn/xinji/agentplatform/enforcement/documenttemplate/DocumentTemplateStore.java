package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface DocumentTemplateStore {
    Optional<Revision> latest(String templateId);
    Optional<Revision> revision(String templateId, long revision);
    List<Summary> list(String organization);
    void add(Revision revision);
    void transition(String templateId, long revision, State expected, State target, String evidenceRef);
    void addPreview(PreviewArtifact preview);
    Optional<PreviewArtifact> preview(String templateId, String previewId);
    <T> T command(String requestKey, String fingerprint, String actor, String operation, Supplier<T> action, Class<T> type);
}
