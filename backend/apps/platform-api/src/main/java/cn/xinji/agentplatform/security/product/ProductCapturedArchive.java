package cn.xinji.agentplatform.security.product;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Validates complete tar members inside a bounded captured gzip prefix; never extracts paths. */
final class ProductCapturedArchive {
    record Member(String path, long headerOffset, long contentOffset, String sha) { }
    private static final long MAX_EXPANDED = 128L * 1024 * 1024;

    void verify(byte[] prefix, List<Member> expected) {
        if (expected.isEmpty() || expected.size() > 100) throw new IllegalStateException("Bounded archive inventory required");
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(prefix))) {
            long offset = 0;
            int index = 0;
            while (index < expected.size()) {
                byte[] header = exactly(input, 512);
                if (Arrays.equals(header, new byte[512])) throw new IllegalStateException("Archive ended before inventory");
                long checksum = 0;
                for (int i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                if (checksum != octal(header, 148, 8)) throw new IllegalStateException("Tar checksum mismatch");
                String name = text(header, 0, 100), parent = text(header, 345, 155);
                String path = parent.isEmpty() ? name : parent + "/" + name;
                if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                        || path.chars().anyMatch(c -> c < 32) || List.of(path.split("/", -1)).contains(".."))
                    throw new IllegalStateException("Unsafe tar member path");
                long size = octal(header, 124, 12);
                String type = text(header, 156, 1);
                if (!List.of("", "0", "5").contains(type) || type.equals("5") && size != 0)
                    throw new IllegalStateException("Unsupported tar member type");
                long padded = Math.multiplyExact((Math.addExact(size, 511) / 512), 512);
                if (Math.addExact(offset, Math.addExact(512, padded)) > MAX_EXPANDED)
                    throw new IllegalStateException("Expanded archive limit exceeded");
                byte[] content = exactly(input, Math.toIntExact(size));
                if (!type.equals("5") && path.endsWith(".json")) {
                    Member member = expected.get(index++);
                    if (!member.path().equals(path) || member.headerOffset() != offset
                            || member.contentOffset() != offset + 512 || !member.sha().equals(digest(content)))
                        throw new IllegalStateException("Captured archive member binding differs");
                }
                exactly(input, Math.toIntExact(padded - size));
                offset += 512 + padded;
            }
            // Remaining prefix and full gzip CRC are deliberately not represented as full-archive validation.
        } catch (IOException | ArithmeticException invalid) {
            throw new IllegalStateException("Captured archive prefix cannot be verified", invalid);
        }
    }

    private static byte[] exactly(InputStream input, int count) throws IOException {
        byte[] bytes = input.readNBytes(count);
        if (bytes.length != count) throw new IllegalStateException("Incomplete captured tar member");
        return bytes;
    }
    private static long octal(byte[] header, int offset, int count) {
        String number = text(header, offset, count).strip();
        if (!number.matches("[0-7]+")) throw new IllegalStateException("Invalid tar number");
        try { return Long.parseLong(number, 8); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("Unsafe tar number", invalid); }
    }
    private static String text(byte[] bytes, int offset, int count) {
        int end = offset;
        while (end < offset + count && bytes[end] != 0) end++;
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, offset, end - offset)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalStateException("Invalid archive UTF-8", invalid); }
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
