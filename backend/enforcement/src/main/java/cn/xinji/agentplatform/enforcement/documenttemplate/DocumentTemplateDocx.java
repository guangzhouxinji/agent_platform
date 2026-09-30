package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.Field;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class DocumentTemplateDocx {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z0-9_.\\[\\]]+)}}");
    private static final Pattern ROW = Pattern.compile("(?s)<w:tr(?:\\s[^>]*)?>.*?</w:tr>");
    private static final long MAX_ENTRY = 10L * 1024 * 1024;
    private static final long MAX_TOTAL = 20L * 1024 * 1024;

    Validation validate(byte[] docx, List<Field> fields) {
        Map<String, byte[]> entries = unzip(docx);
        require(entries.containsKey("[Content_Types].xml") && entries.containsKey("word/document.xml"),
                "Invalid DOCX package");
        require(entries.keySet().stream().noneMatch(this::executable), "Macro or executable content forbidden");
        entries.forEach((name, bytes) -> {
            if (name.endsWith(".rels")) require(!text(bytes).matches("(?s).*TargetMode\\s*=\\s*[\"']External[\"'].*"),
                    "External relationship forbidden: " + name);
        });
        Set<String> declared = new HashSet<>();
        for (Field field : fields) {
            require(field != null && field.id() != null && field.id().matches("[A-Za-z][A-Za-z0-9_]*(\\[\\])?(\\.[A-Za-z][A-Za-z0-9_]*)?"),
                    "Invalid field id");
            require(declared.add(field.id()), "Duplicate field: " + field.id());
            require(field.repeat() == field.id().contains("[]"), "Repeat marker mismatch: " + field.id());
        }
        Map<String, Integer> found = new HashMap<>();
        entries.forEach((name, bytes) -> {
            if (name.startsWith("word/") && name.endsWith(".xml")) {
                Matcher matcher = TOKEN.matcher(text(bytes));
                while (matcher.find()) found.merge(matcher.group(1), 1, Integer::sum);
            }
        });
        for (String value : found.keySet()) require(declared.contains(value), "Unknown placeholder: " + value);
        for (String value : declared) require(found.containsKey(value), "Unused field: " + value);
        validateRepeatRows(text(entries.get("word/document.xml")), fields);
        return new Validation(entries, Map.copyOf(found));
    }

    byte[] render(byte[] docx, List<Field> fields, Map<String, Object> values) {
        Validation validation = validate(docx, fields);
        for (Field field : fields) {
            if (field.required() && !field.repeat()) require(present(values.get(field.id())), "Missing required value: " + field.id());
        }
        Map<String, byte[]> output = new HashMap<>(validation.entries());
        output.replaceAll((name, bytes) -> name.startsWith("word/") && name.endsWith(".xml")
                ? transform(text(bytes), fields, values).getBytes(StandardCharsets.UTF_8) : bytes);
        return zip(output);
    }

    private String transform(String xml, List<Field> fields, Map<String, Object> values) {
        Matcher rows = ROW.matcher(xml);
        StringBuffer result = new StringBuffer();
        while (rows.find()) {
            String row = rows.group();
            List<Field> repeated = fields.stream().filter(Field::repeat).filter(f -> row.contains("{{" + f.id() + "}}" )).toList();
            if (repeated.isEmpty()) continue;
            String root = repeated.getFirst().id().substring(0, repeated.getFirst().id().indexOf("[]"));
            Object raw = values.get(root);
            require(raw instanceof List<?>, "Missing repeat values: " + root);
            StringBuilder replacement = new StringBuilder();
            for (Object item : (List<?>) raw) {
                require(item instanceof Map<?, ?>, "Invalid repeat value: " + root);
                String rendered = row;
                for (Field field : repeated) {
                    String child = field.id().substring(field.id().indexOf("].") + 2);
                    Object value = ((Map<?, ?>) item).get(child);
                    require(!field.required() || present(value), "Missing required value: " + field.id());
                    rendered = rendered.replace("{{" + field.id() + "}}", escape(value));
                }
                replacement.append(rendered);
            }
            rows.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        rows.appendTail(result);
        String plain = result.toString();
        for (Field field : fields) if (!field.repeat())
            plain = plain.replace("{{" + field.id() + "}}", escape(values.get(field.id())));
        require(!TOKEN.matcher(plain).find(), "Unresolved placeholder");
        return plain;
    }

    private static void validateRepeatRows(String xml, List<Field> fields) {
        for (Field field : fields.stream().filter(Field::repeat).toList()) {
            Matcher matcher = ROW.matcher(xml); int count = 0;
            while (matcher.find()) if (matcher.group().contains("{{" + field.id() + "}}")) count++;
            require(count == 1, "Repeat placeholder requires one table row: " + field.id());
        }
    }

    private Map<String, byte[]> unzip(byte[] content) {
        require(content != null && content.length > 0 && content.length <= MAX_TOTAL, "DOCX size invalid");
        Map<String, byte[]> entries = new HashMap<>(); long total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                require(!entry.isDirectory() && !entry.getName().contains("..") && entries.size() < 200, "Unsafe DOCX entry");
                byte[] bytes = input.readNBytes((int) MAX_ENTRY + 1);
                require(bytes.length <= MAX_ENTRY, "DOCX entry too large"); total += bytes.length;
                require(total <= MAX_TOTAL, "DOCX expands beyond limit"); entries.put(entry.getName(), bytes);
            }
            return entries;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Unreadable DOCX", e); }
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (var item : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                out.putNextEntry(new ZipEntry(item.getKey())); out.write(item.getValue()); out.closeEntry();
            }
            out.finish(); return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("DOCX rendering failed", e); }
    }

    private boolean executable(String name) {
        String lower = name.toLowerCase(); return lower.endsWith("vbaproject.bin") || lower.endsWith(".js") || lower.endsWith(".exe");
    }
    private static boolean present(Object value) { return value != null && !(value instanceof String s && s.isBlank()); }
    private static String escape(Object value) { return value == null ? "" : String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    record Validation(Map<String, byte[]> entries, Map<String, Integer> placeholders) { }
}
