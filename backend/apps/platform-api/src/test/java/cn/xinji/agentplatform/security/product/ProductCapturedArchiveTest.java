package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** Constructed archive envelopes test parser boundaries; the JSON body is a retained real original. */
class ProductCapturedArchiveTest {
    private byte[] original() throws Exception {
        return Files.readAllBytes(Path.of("data/patents/hupd-real-candidate-20261002-v2/raw/member-0001.json"));
    }
    private byte[] header(String path, long size, String type) {
        byte[] header = new byte[512];
        System.arraycopy(path.getBytes(StandardCharsets.UTF_8), 0, header, 0, path.length());
        write(header, 124, String.format("%011o\0", size));
        write(header, 156, type);
        write(header, 257, "ustar\0");
        checksum(header);
        return header;
    }
    private void write(byte[] bytes, int start, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, start, encoded.length);
    }
    private void checksum(byte[] header) {
        Arrays.fill(header, 148, 156, (byte) 32);
        long sum = 0;
        for (byte value : header) sum += value & 255;
        write(header, 148, String.format("%06o\0 ", sum));
    }
    private byte[] gzip(byte[] header, byte[] body) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(header); gzip.write(body); gzip.write(new byte[(512 - body.length % 512) % 512]);
        }
        return output.toByteArray();
    }
    private List<ProductCapturedArchive.Member> expected(String path, byte[] bytes) {
        return List.of(new ProductCapturedArchive.Member(path, 0, 512, ProductCapturedArchive.digest(bytes)));
    }

    @Test void validEnvelopeBindsTheCompleteRetainedBody() throws Exception {
        byte[] bytes = original();
        new ProductCapturedArchive().verify(gzip(header("record.json", bytes.length, "0"), bytes), expected("record.json", bytes));
    }
    @Test void corruptedHeaderAndUnsafeNamesReject() throws Exception {
        byte[] bytes = original(), corrupted = header("record.json", bytes.length, "0");
        corrupted[0] ^= 1;
        byte[] bad = gzip(corrupted, bytes);
        assertThatThrownBy(() -> new ProductCapturedArchive().verify(bad, expected("record.json", bytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum");
        for (String path : List.of("../record.json", "/record.json", "C:/record.json", "dir\\record.json")) {
            byte[] archive = gzip(header(path, bytes.length, "0"), bytes);
            assertThatThrownBy(() -> new ProductCapturedArchive().verify(archive, expected(path, bytes)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsafe");
        }
    }
    @Test void linksInvalidSizesAndDecompressionLimitsReject() throws Exception {
        byte[] bytes = original();
        for (byte[] header : List.of(header("record.json", bytes.length, "2"),
                header("record.json", 129L * 1024 * 1024, "0"))) {
            byte[] archive = gzip(header, bytes);
            assertThatThrownBy(() -> new ProductCapturedArchive().verify(archive, expected("record.json", bytes)))
                    .isInstanceOf(IllegalStateException.class);
        }
        byte[] invalid = header("record.json", bytes.length, "0");
        invalid[124] = '9'; checksum(invalid);
        byte[] bad = gzip(invalid, bytes);
        assertThatThrownBy(() -> new ProductCapturedArchive().verify(bad, expected("record.json", bytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("number");
    }
    @Test void partialBodiesAndWrongContentBindingsReject() throws Exception {
        byte[] bytes = original();
        byte[] partial = gzip(header("record.json", bytes.length, "0"), Arrays.copyOf(bytes, 32));
        assertThatThrownBy(() -> new ProductCapturedArchive().verify(partial, expected("record.json", bytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Incomplete");
        byte[] archive = gzip(header("record.json", bytes.length, "0"), bytes);
        assertThatThrownBy(() -> new ProductCapturedArchive().verify(archive,
                List.of(new ProductCapturedArchive.Member("record.json", 0, 512, "0".repeat(64)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("binding");
    }
}
