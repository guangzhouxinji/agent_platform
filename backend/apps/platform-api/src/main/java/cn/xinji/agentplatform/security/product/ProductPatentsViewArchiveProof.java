package cn.xinji.agentplatform.security.product;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Streams the complete archived member and matches captured original row byte ranges. */
final class ProductPatentsViewArchiveProof {
    record Segment(long start, long end, byte[] original) {
        Segment {
            if (start < 0 || end <= start || original == null || end - start != original.length)
                throw new IllegalArgumentException("Exact original segment required");
            original = original.clone();
        }
    }

    static void verify(InputStream archive, String member, List<Segment> segments, String md5) throws IOException {
        var sorted = segments.stream().sorted(Comparator.comparingLong(Segment::start)).toList();
        long previous = -1;
        for (Segment segment : sorted) {
            if (segment.start() < previous) throw new IllegalStateException("Overlapping original ranges");
            previous = segment.end();
        }
        try {
            var checksum = java.security.MessageDigest.getInstance("MD5");
            var original = new java.security.DigestInputStream(archive, checksum);
            var zip = new ZipInputStream(original);
            var entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory() || !member.equals(entry.getName()))
                throw new IllegalStateException("Unexpected ZIP member");
            byte[] buffer = new byte[65536];
            long offset = 0;
            int cursor = 0, count;
            while ((count = zip.read(buffer)) != -1) {
                long end = offset + count;
                if (end > 2L * 1024 * 1024 * 1024) throw new IllegalStateException("Expanded archive exceeds budget");
                while (cursor < sorted.size() && sorted.get(cursor).start() < end) {
                    Segment segment = sorted.get(cursor);
                    long from = Math.max(offset, segment.start()), to = Math.min(end, segment.end());
                    for (long position = from; position < to; position++)
                        if (buffer[Math.toIntExact(position - offset)] != segment.original()[Math.toIntExact(position - segment.start())])
                            throw new IllegalStateException("Archived original row differs");
                    if (segment.end() <= end) cursor++; else break;
                }
                offset = end;
            }
            if (cursor != sorted.size() || zip.getNextEntry() != null)
                throw new IllegalStateException("Missing original ranges or extra ZIP member");
            // Include the central directory/trailing bytes in the published full ZIP MD5.
            while (original.read(buffer) != -1) { }
            if (!java.util.HexFormat.of().formatHex(checksum.digest()).equals(md5))
                throw new IllegalStateException("Published archive checksum differs");
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
