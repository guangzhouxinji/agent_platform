package cn.xinji.agentplatform.consultation.patenttask.source;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic hard constraints for explicit queries; natural language remains soft retrieval. */
public final class PatentQuerySyntax {
    public static final String VERSION = "PATENT_QUERY/v1";
    public static final String FIELD_VERSION = "PATENT_QUERY_FIELDS/v2";
    private static final Set<String> TEXT_FIELDS = Set.of("TITLE", "ABSTRACT", "CLAIMS", "DESCRIPTION");
    private static final Pattern NUMBER = Pattern.compile("(?i)(?:US|CN|EP|WO|JP|KR|GB|DE)[- ]?\\d{6,13}[- ]?[ABCUTY]\\d?");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*");
    public static final class InvalidQuery extends IllegalArgumentException {
        public InvalidQuery(String reason) { super(reason); }
    }
    private interface Node { boolean matches(PatentLexicalQuery.Document document); }
    public static final class Plan {
        private final Node root;
        private final boolean explicit;
        private final String positiveText;
        private final String version;
        private Plan(Node root, boolean explicit, String positiveText, String version) {
            this.root = root; this.explicit = explicit; this.positiveText = positiveText; this.version = version;
        }
        public String version() { return version; }
        public boolean explicit() { return explicit; }
        public String positiveText() { return positiveText; }
        public boolean matches(PatentLexicalQuery.Document document) {
            return !explicit || root.matches(document);
        }
        public boolean exactNumber() { return root instanceof Term term && term.field().equals("DOCUMENT_ID"); }
    }
    private record Token(String value, boolean phrase) { }
    private record Term(String field, String value, boolean phrase) implements Node {
        public boolean matches(PatentLexicalQuery.Document document) {
            if (field.equals("DOCUMENT_ID")) return number(document.documentId()).equals(number(value));
            String text = document.fields().get(field);
            if (text == null) return false;
            if (value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN))
                return fold(text).contains(fold(value));
            List<String> words = words(text), expected = words(value);
            if (!phrase) return words.containsAll(expected);
            return java.util.Collections.indexOfSubList(words, expected) >= 0;
        }
    }
    private record Binary(Node left, Node right, boolean and) implements Node {
        public boolean matches(PatentLexicalQuery.Document document) {
            return and ? left.matches(document) && right.matches(document)
                    : left.matches(document) || right.matches(document);
        }
    }
    private record Negation(Node child) implements Node {
        public boolean matches(PatentLexicalQuery.Document document) { return !child.matches(document); }
    }

    public Plan parse(String query, Set<String> admittedFields) {
        return parse(query, admittedFields, false);
    }

    /** The calling product service owns current source/Task/DataScope authorization. */
    public Plan parseFields(String query, Set<String> admittedFields) {
        if (admittedFields == null || admittedFields.isEmpty()
                || admittedFields.stream().anyMatch(field -> field == null || !TEXT_FIELDS.contains(field)))
            throw invalid("Query field scope invalid");
        return parse(query, Set.copyOf(admittedFields), true);
    }

    private Plan parse(String query, Set<String> admittedFields, boolean fieldAware) {
        if (query == null || query.isBlank()
                || query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4000 || admittedFields == null)
            throw invalid("Query size or field scope invalid");
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFC).strip();
        List<Token> tokens = tokenize(normalized);
        boolean publication = NUMBER.matcher(normalized).matches();
        boolean explicit = publication || tokens.stream().anyMatch(t -> t.phrase()
                || List.of("AND", "OR", "NOT", "(", ")", ":").contains(t.value()));
        String version = fieldAware ? FIELD_VERSION : VERSION;
        if (!explicit) {
            if (words(normalized).isEmpty()) throw invalid("Query has no searchable terms");
            return new Plan(null, false, normalized, version);
        }
        if (publication) return new Plan(new Term("DOCUMENT_ID", normalized, false), true, normalized, version);
        Parser parser = new Parser(tokens, admittedFields, fieldAware);
        Node root = parser.or(fieldAware ? null : "TITLE", 0, false);
        if (parser.index != tokens.size()) throw invalid("Unexpected query token");
        if (parser.positive.isEmpty()) throw invalid("Positive query operand required");
        if (!bounded(root)) throw invalid("Each alternative requires a positive query operand");
        if (parser.identity && !(root instanceof Term term && term.field().equals("DOCUMENT_ID")))
            throw invalid("Publication number must be a standalone exact query");
        return new Plan(root, true, String.join(" ", parser.positive), version);
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Set<String> fields;
        private final List<String> positive = new ArrayList<>();
        private int index;
        private boolean identity;
        private final boolean fieldAware;
        private Parser(List<Token> tokens, Set<String> fields, boolean fieldAware) {
            this.tokens = tokens; this.fields = fields; this.fieldAware = fieldAware;
        }
        private boolean at(String value) {
            return index < tokens.size() && !tokens.get(index).phrase() && tokens.get(index).value().equals(value);
        }
        private boolean take(String value) { if (!at(value)) return false; index++; return true; }
        private Node or(String field, int depth, boolean negative) {
            Node result = and(field, depth, negative);
            while (index < tokens.size() && !at(")")) {
                take("OR");
                result = new Binary(result, and(field, depth, negative), false);
            }
            return result;
        }
        private Node and(String field, int depth, boolean negative) {
            Node result = unary(field, depth, negative);
            while (take("AND")) result = new Binary(result, unary(field, depth, negative), true);
            return result;
        }
        private Node unary(String field, int depth, boolean negative) {
            if (depth > 16) throw invalid("Query nesting limit exceeded");
            if (take("NOT")) return new Negation(unary(field, depth + 1, !negative));
            if (take("(")) {
                Node result = or(field, depth + 1, negative);
                if (!take(")")) throw invalid("Unclosed query group");
                return result;
            }
            if (index >= tokens.size() || at("AND") || at("OR") || at(")") || at(":"))
                throw invalid("Query operand missing");
            Token term = tokens.get(index++);
            if (!term.phrase() && take(":")) {
                String requested = term.value().toUpperCase(Locale.ROOT);
                if (!requested.equals("DOCUMENT_ID") && (!(fieldAware ? TEXT_FIELDS : Set.of("TITLE")).contains(requested)
                        || !fields.contains(requested)))
                    throw invalid("Query field not admitted");
                return unary(requested, depth + 1, negative);
            }
            if ("TITLE".equals(field) && !fields.contains("TITLE")) throw invalid("Title field not admitted");
            if ("DOCUMENT_ID".equals(field) && !NUMBER.matcher(term.value()).matches())
                throw invalid("Exact publication number required");
            if ("DOCUMENT_ID".equals(field)) identity = true;
            if (words(term.value()).isEmpty()) throw invalid("Empty query operand");
            if (!negative) positive.add(term.value());
            if (field != null) return new Term(field, term.value(), term.phrase());
            Node union = null;
            for (String selected : fields.stream().sorted().toList()) {
                Node operand = new Term(selected, term.value(), term.phrase());
                union = union == null ? operand : new Binary(union, operand, false);
            }
            return union;
        }
    }

    private static List<Token> tokenize(String query) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < query.length();) {
            char ch = query.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (ch == '?') {
                int end = i; while (end < query.length() && query.charAt(end) == '?') end++;
                if (i > 0 && Character.isLetterOrDigit(query.charAt(i - 1))
                        || end < query.length() && Character.isLetterOrDigit(query.charAt(end)))
                    throw invalid("Wildcard syntax is not supported");
                i = end; continue;
            }
            if ("*?~^[]{}\\&|".indexOf(ch) >= 0) throw invalid("Unsupported query syntax");
            if ("():".indexOf(ch) >= 0) { result.add(new Token(String.valueOf(ch), false)); i++; }
            else if (ch == '"') {
                int end = query.indexOf('"', i + 1);
                if (end < 0) throw invalid("Unclosed phrase");
                String phrase = query.substring(i + 1, end);
                if (phrase.isBlank()) throw invalid("Empty phrase");
                result.add(new Token(phrase, true)); i = end + 1;
            } else {
                int start = i;
                while (i < query.length() && !Character.isWhitespace(query.charAt(i))
                        && "():\"*?~^[]{}\\&|".indexOf(query.charAt(i)) < 0) i++;
                result.add(new Token(query.substring(start, i), false));
            }
            if (result.size() > 128) throw invalid("Query token limit exceeded");
        }
        return List.copyOf(result);
    }
    private static List<String> words(String value) {
        var matcher = WORD.matcher(fold(value));
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        return List.copyOf(result);
    }
    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
    private static String number(String value) { return fold(value).replaceAll("[^a-z0-9]", ""); }
    private static boolean bounded(Node node) {
        if (node instanceof Term) return true;
        if (node instanceof Negation n) return n.child() instanceof Negation inner && bounded(inner.child());
        Binary binary = (Binary) node;
        return binary.and() ? bounded(binary.left()) || bounded(binary.right())
                : bounded(binary.left()) && bounded(binary.right());
    }
    private static InvalidQuery invalid(String reason) { return new InvalidQuery(reason); }
}
