package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Encoding equivalence only, not source admission or authorization acceptance. */
class ProductHistoryDigestFramingTest {
    @Test void preservesNullEmptyUnicodeSurrogatesAndLengthBoundaries() throws Exception {
        var values = new ArrayList<String>();
        values.add(null);
        values.addAll(List.of("", "-1:", "0:", "组织𠮷", "a\u0000:b", "\uD800",
                "\uDC00", "\uD800x\uDC00", "é", "e\u0301"));
        for (int length : new int[] {1, 9, 10, 99, 100, 999, 1000, 9999, 10000})
            values.add("数".repeat(length));
        var random = new Random(20261001L);
        for (int row = 0; row < 2000; row++) {
            char[] text = new char[random.nextInt(128)];
            for (int i = 0; i < text.length; i++) text[i] = (char) random.nextInt(65536);
            values.add(new String(text));
        }
        assertThat(actual(values)).isEqualTo(original(values));
        var changed = new ArrayList<>(values);
        changed.set(0, "");
        assertThat(actual(changed)).isNotEqualTo(actual(values));
        assertThat(actual(List.of("ab", "c"))).isNotEqualTo(actual(List.of("a", "bc")));
    }

    @Test void concurrentDigestsNeverShareMutablePrefixOrDigestState() throws Exception {
        try (var pool = Executors.newFixedThreadPool(10)) {
            var jobs = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 100; i++) {
                var values = List.of("组织𠮷".repeat(i + 1), Integer.toString(i), "", ":");
                jobs.add(() -> java.util.Arrays.equals(actual(values), original(values)));
            }
            for (var result : pool.invokeAll(jobs)) assertThat(result.get()).isTrue();
        }
    }

    private static byte[] actual(List<String> values) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        var framing = new ProductHistoryDigestFraming(hash);
        values.forEach(framing::add);
        return hash.digest();
    }

    private static byte[] original(List<String> values) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        for (var value : values)
            hash.update((value == null ? "-1:" : value.length() + ":" + value)
                    .getBytes(StandardCharsets.UTF_8));
        return hash.digest();
    }
}
