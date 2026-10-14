package cn.xinji.agentplatform.security.product;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Read-only original bytes for an explicitly selected immutable document, never a model summary. */
final class ProductPatentDocumentContent {
    record Content(String taskId, String resultSetId, String selectionId, long selectionRevision,
                   String memberId, String sourceId, String documentId, String documentVersion,
                   String sourceVersion, String rawSha, String kind, String field,
                   String locatorRef, String parserVersion, String text, Instant retrievedAt) { }
    private final ProductPatentSelection selections;
    private final ProductSourceAvailability availability;
    private final ProductCollectedPatentTitles originals;
    private final Clock clock;
    private final ProductPatentDocumentAccess access;

    ProductPatentDocumentContent(ProductPatentSelection selections, ProductSourceAvailability availability,
            ProductCollectedPatentTitles originals, ProductPatentDocumentAccess access, Clock clock) {
        this.selections = selections; this.availability = availability;
        this.originals = originals; this.access = access; this.clock = clock;
    }

    Content read(String taskId, String resultSetId, String selectionId, String sourceId,
            String documentId, String version, String kind, String locator, Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String field = switch (kind == null ? "" : kind) {
            case "CLAIMS" -> "CLAIMS";
            case "FULL_TEXT" -> "DESCRIPTION";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        if (blank(taskId) || blank(resultSetId) || blank(selectionId) || blank(sourceId)
                || blank(documentId) || blank(version))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var selection = selections.read(taskId, resultSetId, selectionId, jwt);
        var selected = selection.members().stream().filter(member -> sourceId.equals(member.sourceId())
                && documentId.equals(member.documentId()) && version.equals(member.documentVersion())).toList();
        if (selected.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var member = selected.getFirst();
        var active = availability.require(sourceId, field, "DISPLAY");
        if (!active.sourceVersion().equals(member.sourceVersion())) throw unavailable();
        var authority = access.require(taskId, active, jwt);
        var original = original(active, member, field);
        String reference = original.fieldLocators().get(field);
        String text = original.document().fields().get(field);
        if (blank(reference) || blank(text) || !reference.contains("#json-pointer=")
                || !reference.contains("&utf8=")
                || !file(reference).equals(file(member.locatorRef()))) throw unavailable();
        if (locator != null && !reference.equals(locator))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        // Reread the exact original and all current guards before releasing any field text.
        if (!original.equals(original(active, member, field))
                || !selection.equals(selections.read(taskId, resultSetId, selectionId, jwt))
                || !active.equals(availability.require(sourceId, field, "DISPLAY"))
                || !authority.equals(access.require(taskId, active, jwt))) throw unavailable();
        return new Content(taskId, resultSetId, selectionId, selection.revision(), member.memberId(),
                sourceId, documentId, version, member.sourceVersion(), member.rawSha(), kind, field,
                reference, "CAPTURED_JSON_UTF8_TOKEN/v1", text, clock.instant());
    }

    private cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence
            original(ProductSourceAvailability.Active active, ProductPatentSelection.Member member, String field) {
        var rows = originals.loadFieldEvidence(active, ProductPatentSearchFilters.none(), Set.of(field)).stream()
                .filter(row -> row.document().sourceId().equals(member.sourceId())
                        && row.document().documentId().equals(member.documentId())).toList();
        if (rows.size() != 1) throw unavailable();
        var row = rows.getFirst(); var document = row.document();
        if (!member.documentVersion().equals(document.documentVersion())
                || !member.sourceVersion().equals(document.sourceVersion())
                || !member.rawSha().equals(document.rawSha())) throw unavailable();
        return row;
    }
    private static String file(String locator) {
        if (blank(locator) || locator.indexOf('#') <= 0) throw unavailable();
        return locator.substring(0, locator.indexOf('#'));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Selected original content is unavailable");
    }
}
