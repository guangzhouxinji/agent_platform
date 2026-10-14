package cn.xinji.agentplatform.enforcement.legalreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;

/** Versioned file adapter; it never supplies built-in or inferred legal candidates. */
public final class FileLegalKnowledgeSource implements LegalKnowledgePort {
    public record Catalog(List<Entry> sources, Provenance provenance) {
        public Catalog(List<Entry> sources) { this(sources, null); }
    }
    public record Provenance(String provider, String sourceUrl, String snapshot, String snapshotSha256,
                             String capturedAt, String purpose, String legalApplicability) { }
    public record Entry(String sourceId, long sourceRevision, String sourceVersion, String status, String failure,
                        List<Candidate> candidates) { }
    private final Path file;
    private final ObjectMapper json;

    public FileLegalKnowledgeSource(Path file, ObjectMapper json) {
        this.file = file; this.json = json;
    }

    @Override public List<SourceResult> search(SearchRequest request) {
        Catalog catalog = catalog();
        var results = new ArrayList<SourceResult>();
        for (String requested : request.sourceRefs()) {
            var matching = catalog.sources().stream().filter(e -> requested.equals(e.sourceId()))
                    .max(Comparator.comparing(Entry::sourceVersion));
            if (matching.isEmpty()) {
                results.add(new SourceResult(requested, 1, "UNKNOWN", "FAILED", List.of(), "SOURCE_UNAVAILABLE"));
                continue;
            }
            Entry entry = matching.get();
            if (!"AVAILABLE".equals(entry.status())) {
                results.add(new SourceResult(entry.sourceId(), entry.sourceRevision(), entry.sourceVersion(),
                        "FAILED", List.of(),
                        blank(entry.failure()) ? "SOURCE_UNAVAILABLE" : entry.failure()));
                continue;
            }
            String query = request.query().toLowerCase(Locale.ROOT);
            if (entry.candidates() == null) throw new IllegalStateException("Invalid legal source snapshot");
            List<Candidate> candidates = entry.candidates().stream().peek(c -> validate(entry, c))
                    .filter(c -> searchable(c).toLowerCase(Locale.ROOT).contains(query)).toList();
            results.add(new SourceResult(entry.sourceId(), entry.sourceRevision(), entry.sourceVersion(),
                    "COMPLETED", candidates, null));
        }
        return List.copyOf(results);
    }

    @Override public Candidate exact(String sourceId, String sourceVersion, String candidateId) {
        return catalog().sources().stream().filter(e -> sourceId.equals(e.sourceId()) &&
                        sourceVersion.equals(e.sourceVersion()) && "AVAILABLE".equals(e.status()))
                .flatMap(e -> e.candidates().stream().peek(c -> validate(e, c)))
                .filter(c -> candidateId.equals(c.candidateId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Legal source unavailable"));
    }

    @Override public Candidate latest(String sourceId, String articleId) {
        return catalog().sources().stream().filter(e -> sourceId.equals(e.sourceId()) &&
                        "AVAILABLE".equals(e.status()))
                .sorted(Comparator.comparing(Entry::sourceVersion).reversed())
                .flatMap(e -> e.candidates().stream().peek(c -> validate(e, c)))
                .filter(c -> articleId.equals(c.articleId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Legal source unavailable"));
    }

    private Catalog catalog() {
        try {
            if (file == null || !Files.isRegularFile(file))
                throw new IllegalStateException("Legal source catalog unavailable");
            Catalog value = json.readValue(Files.readAllBytes(file), Catalog.class);
            if (value == null || value.sources() == null || value.sources().isEmpty())
                throw new IllegalStateException("Legal source catalog unavailable");
            validate(value.provenance());
            return value;
        } catch (IllegalStateException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Legal source catalog unavailable", failure); }
    }

    private void validate(Entry entry, Candidate c) {
        if (c == null || blank(c.candidateId()) || blank(c.sourceId()) || blank(c.sourceVersion()) ||
                blank(c.articleId()) || blank(c.officialTitle()) || blank(c.locator()) ||
                blank(c.verbatimText()) || blank(c.verbatimSha256()) || c.explanation() == null ||
                c.gaps() == null || !"REVIEW_REQUIRED".equals(c.reviewStatus()) ||
                entry.sourceRevision() < 1 || c.sourceRevision() != entry.sourceRevision() ||
                !entry.sourceId().equals(c.sourceId()) || !entry.sourceVersion().equals(c.sourceVersion()) ||
                !LegalReferenceService.hash(c.verbatimText()).equals(c.verbatimSha256()))
            throw new IllegalStateException("Invalid legal source snapshot");
    }
    private void validate(Provenance value) {
        if (value == null) throw new IllegalStateException("Legal source provenance unavailable");
        if (blank(value.provider()) || blank(value.sourceUrl()) || blank(value.snapshot()) ||
                value.snapshotSha256() == null || !value.snapshotSha256().matches("[0-9a-f]{64}") ||
                blank(value.capturedAt()) || blank(value.purpose()) || blank(value.legalApplicability()))
            throw new IllegalStateException("Invalid legal source provenance");
        try {
            Path snapshot = Path.of(value.snapshot());
            if (!snapshot.isAbsolute()) {
                Path root = file.toAbsolutePath().getParent();
                while (root != null && !Files.isDirectory(root.resolve("db/dm8"))) root = root.getParent();
                snapshot = (root == null ? file.toAbsolutePath().getParent() : root).resolve(snapshot).normalize();
            }
            byte[] bytes = Files.readAllBytes(snapshot);
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!value.snapshotSha256().equals(actual)) throw new IllegalStateException("Legal original digest changed");
        } catch (IllegalStateException invalid) { throw invalid; }
        catch (Exception unavailable) { throw new IllegalStateException("Legal original unavailable", unavailable); }
    }
    private static String searchable(Candidate c) {
        return c.officialTitle() + " " + c.articleId() + " " + c.verbatimText();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
