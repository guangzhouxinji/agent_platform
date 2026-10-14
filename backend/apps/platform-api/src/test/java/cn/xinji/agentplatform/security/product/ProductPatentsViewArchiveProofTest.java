package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mechanical bytes only; never a patent record or an admitted Source. */
class ProductPatentsViewArchiveProofTest {
    @TempDir Path root;
    private String hash(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
    private byte[] zip(byte[] bytes, boolean extra) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("opaque.tsv")); archive.write(bytes); archive.closeEntry();
            if (extra) { archive.putNextEntry(new ZipEntry("extra")); archive.write(1); archive.closeEntry(); }
        }
        return output.toByteArray();
    }
    @Test void fullCompressedHashCrcAndOriginalRangeShareTheConfiguredRootBudget() throws Exception {
        byte[] bytes = "header\nfirst\nnext\n".getBytes(StandardCharsets.UTF_8), archive = zip(bytes,false);
        Files.write(root.resolve("actual.zip"),archive);
        String md5 = hash(archive,"MD5"), sha = hash(archive,"SHA-256");
        var segment = new ProductPatentsViewArchiveProof.Segment(7,13,"first\n".getBytes(StandardCharsets.UTF_8));
        var files = new ProductSourceEvidenceFiles(root,archive.length);
        files.verifiedStream("actual.zip",sha,archive.length,
                stream -> ProductPatentsViewArchiveProof.verify(stream,"opaque.tsv",List.of(segment),md5));
        assertThatThrownBy(() -> files.verifiedStream("actual.zip","0".repeat(64),archive.length,stream -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("digest changed");
        assertThatThrownBy(() -> new ProductSourceEvidenceFiles(root,archive.length-1).archivePresent("actual.zip",sha,archive.length))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.archivePresent("../actual.zip",sha,archive.length)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wrongSpanExtraMemberAndWrongPublicChecksumFailEvenWithUnchangedFileHash() throws Exception {
        byte[] raw = "header\nbody\n".getBytes(StandardCharsets.UTF_8), archive = zip(raw,false);
        var wrong = new ProductPatentsViewArchiveProof.Segment(7,12,"oops\n".getBytes(StandardCharsets.UTF_8));
        String md5 = hash(archive,"MD5");
        assertThatThrownBy(() -> ProductPatentsViewArchiveProof.verify(new ByteArrayInputStream(archive),"opaque.tsv",List.of(wrong),md5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("row differs");
        byte[] extra = zip(raw,true); String extraMd5 = hash(extra,"MD5");
        assertThatThrownBy(() -> ProductPatentsViewArchiveProof.verify(new ByteArrayInputStream(extra),"opaque.tsv",List.of(),extraMd5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("extra ZIP");
        assertThatThrownBy(() -> ProductPatentsViewArchiveProof.verify(new ByteArrayInputStream(archive),"opaque.tsv",List.of(),"0".repeat(32)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum differs");
    }
    @Test void quotedUnicodeTabsNewlinesAndQuotesRemainOriginalValues() {
        String row = "\"RE29015\"\t0\t\"第一行\t\"\"quoted\"\"\n第二行\"\t\"\"\t\"1\"\t0\n";
        var values = ProductPatentsViewCsv.row(row.getBytes(StandardCharsets.UTF_8),ProductPatentsViewCsv.CLAIMS);
        assertThat(values.get("claim_text")).isEqualTo("第一行\t\"quoted\"\n第二行");
        assertThat(values.get("patent_id")).isEqualTo("RE29015");
        assertThatThrownBy(() -> ProductPatentsViewCsv.row(new byte[]{(byte)255},ProductPatentsViewCsv.CLAIMS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("encoding");
        assertThatThrownBy(() -> ProductPatentsViewCsv.row("1\t0\tmissing\n".getBytes(StandardCharsets.UTF_8),ProductPatentsViewCsv.CLAIMS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Incomplete");
    }
}
