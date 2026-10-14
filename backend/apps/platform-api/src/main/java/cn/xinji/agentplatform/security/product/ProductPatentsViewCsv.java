package cn.xinji.agentplatform.security.product;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One complete original TSV row, including quoted tabs/newlines; never a patent interpretation. */
final class ProductPatentsViewCsv {
    static final List<String> CLAIMS = List.of("patent_id", "claim_sequence", "claim_text", "dependent", "claim_number", "exemplary");
    static final List<String> METADATA = List.of("patent_id", "patent_type", "patent_date", "patent_title", "wipo_kind", "num_claims", "withdrawn", "filename");

    static Map<String,String> row(byte[] bytes, List<String> columns) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            List<String> values = new ArrayList<>();
            StringBuilder value = new StringBuilder();
            boolean quoted = false, closed = false, ended = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    int quote = text.indexOf('"', i);
                    if (quote < 0) throw new IllegalStateException("Incomplete TSV row/columns");
                    value.append(text, i, quote);
                    if (quote + 1 < text.length() && text.charAt(quote + 1) == '"') {
                        value.append('"'); i = quote + 1;
                    } else { quoted = false; closed = true; i = quote; }
                } else if (c == '\t') {
                    values.add(value.toString()); value.setLength(0); closed = false;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    if (i != text.length() - 1) throw new IllegalStateException("More than one CSV row");
                    values.add(value.toString()); ended = true;
                } else if (c == '"' && value.isEmpty() && !closed) quoted = true;
                else {
                    if (closed || c == '"') throw new IllegalStateException("Invalid TSV quote");
                    value.append(c);
                }
            }
            if (quoted || !ended || values.size() != columns.size() || new HashSet<>(columns).size() != columns.size())
                throw new IllegalStateException("Incomplete TSV row/columns");
            Map<String,String> result = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) result.put(columns.get(i), values.get(i));
            return Map.copyOf(result);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException("Original TSV encoding invalid", invalid);
        }
    }
}
