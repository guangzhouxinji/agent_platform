package cn.xinji.agentplatform.enforcement.material;

import java.util.ArrayList;
import java.util.List;

/** Input roles are candidates tied to exact source text, never verified identities. */
final class MaterialActorMentions {
    private MaterialActorMentions() { }

    static List<MaterialWork.ActorMention> locate(String observationId, String text,
                                                  List<MaterialWork.ActorInput> inputs) {
        if (inputs == null) return List.of();
        if (inputs.size() > 20) throw new IllegalArgumentException("Too many actor mentions");
        var result = new ArrayList<MaterialWork.ActorMention>();
        for (var input : inputs) {
            if (input == null || input.role() == null || !input.role().matches("[A-Z_]{3,80}")
                    || input.literal() == null || input.literal().isBlank() || input.literal().length() > 300
                    || input.businessCode() != null && input.businessCode().length() > 120)
                throw new IllegalArgumentException("Invalid actor mention");
            int start = text.indexOf(input.literal());
            if (start < 0 || start != text.lastIndexOf(input.literal()))
                throw new IllegalArgumentException("Actor locator must be unique in the source text");
            result.add(new MaterialWork.ActorMention(observationId, result.size() + 1, input.role(),
                    input.literal(), "text:" + start + ":" + input.literal().length(), input.businessCode()));
        }
        return List.copyOf(result);
    }
}
