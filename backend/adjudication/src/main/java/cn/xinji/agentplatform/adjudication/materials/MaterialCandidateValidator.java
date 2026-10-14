package cn.xinji.agentplatform.adjudication.materials;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Mechanical provenance/quote binding; this does NOT establish truth or detect all hallucinations. */
public final class MaterialCandidateValidator {
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public List<Candidate> validate(List<Input> inputs, String raw) {
        var sources = new HashMap<String, Input>();
        for (var i : inputs) {
            if (i == null || i.tag() == null || !i.tag().matches("s[1-9][0-9]{0,2}") || i.side() == null || i.source() == null
                    || i.text() == null || i.text().isBlank() || !sha256(i.text()).equals(i.source().sha256())
                    || sources.putIfAbsent(i.tag(), i) != null) throw invalid("MODEL_INPUT_SOURCE_INVALID");
        }
        if (inputs.isEmpty() || inputs.size()>20 || raw == null || raw.length()>100000) throw invalid("MODEL_INPUT_OR_OUTPUT_LIMIT");
        try {
            JsonNode root = mapper.readTree(raw); fields(root, Set.of("candidates"));
            JsonNode array = root.get("candidates");
            if (array == null || !array.isArray() || array.isEmpty() || array.size()>100) throw invalid("MODEL_CANDIDATES_REQUIRED");
            var result = new ArrayList<Candidate>(); var unique = new HashSet<String>();
            for (var node : array) {
                fields(node, Set.of("kind","sourceTag","side","statement","exactQuote","start","end"));
                Kind kind; Side side;
                try {kind=Kind.valueOf(text(node,"kind")); side=Side.valueOf(text(node,"side"));}
                catch (Exception error) {throw invalid("MODEL_CLASSIFICATION_INVALID");}
                String tag=text(node,"sourceTag"); Input src=sources.get(tag);
                if (src==null || side!=src.side()) throw invalid("MODEL_SOURCE_OR_SIDE_INVALID");
                String quote=text(node,"exactQuote"), statement=text(node,"statement");
                if (statement.length()>2000 || quote.length()>16000) throw invalid("MODEL_INPUT_OR_OUTPUT_LIMIT");
                int start=src.text().indexOf(quote); if(start<0)throw invalid("MODEL_QUOTE_DRIFTED");
                boolean explicit=node.hasNonNull("start") || node.hasNonNull("end");
                if(explicit) {
                    if(!node.path("start").isIntegralNumber() || !node.path("end").isIntegralNumber()
                            || !node.path("start").canConvertToInt() || !node.path("end").canConvertToInt())throw invalid("MODEL_LOCATOR_INVALID");
                    start=node.get("start").intValue();int end=node.get("end").intValue();
                    if(start<0 || end<=start || end>src.text().length() || !src.text().substring(start,end).equals(quote))throw invalid("MODEL_LOCATOR_INVALID");
                } else if(src.text().indexOf(quote,start+1)>=0)throw invalid("MODEL_QUOTE_AMBIGUOUS");
                int end=start+quote.length();
                if((start>0 && Character.isLowSurrogate(src.text().charAt(start)))
                        || (end<src.text().length() && Character.isLowSurrogate(src.text().charAt(end))))throw invalid("MODEL_LOCATOR_INVALID");
                if(!unique.add(kind+"|"+tag+"|"+start+"|"+end))throw invalid("MODEL_CANDIDATE_DUPLICATE");
                result.add(new Candidate("c"+(result.size()+1),kind,side,statement,quote,start,end,
                        src.source().id(),src.source().version(),src.source().sha256(),src.source().locator(),tag));
            }
            return List.copyOf(result);
        } catch(IllegalArgumentException error){throw error;}
        catch(Exception error){throw invalid("MODEL_JSON_INVALID");}
    }
    private static String text(JsonNode n,String key){var v=n.get(key);if(v==null || !v.isTextual() || v.asText().isBlank())throw invalid("MODEL_FIELD_REQUIRED");return v.asText();}
    private static void fields(JsonNode n,Set<String> allowed){if(n==null || !n.isObject())throw invalid("MODEL_JSON_INVALID");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid("MODEL_UNKNOWN_FIELD");});}
    private static IllegalArgumentException invalid(String code){return new IllegalArgumentException(code);}
}
