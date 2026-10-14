package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Exact production-pattern grammar C; none of these strings is a Source or patent record. */
class ProductSourceValidationPatternsTest {
    record Rule(Class<?> type, String field, String original) { }
    private static final List<Rule> RULES = List.of(
            new Rule(ProductSourceEvidenceFiles.class,"REFERENCE","[A-Za-z0-9][A-Za-z0-9._/-]{0,499}"),
            new Rule(ProductSourceEvidenceFiles.class,"SHA","[0-9a-fA-F]{64}"),
            new Rule(ProductPatentsViewRecord.class,"GRANT","[A-Za-z0-9]{1,64}"),
            new Rule(ProductPatentsViewRecord.class,"INTEGER","[0-9]+"),
            new Rule(ProductPatentsViewSourceInventory.class,"ARTIFACT","[A-Za-z0-9][A-Za-z0-9._/-]{0,399}"),
            new Rule(ProductPatentsViewSourceInventory.class,"SHA","[0-9a-f]{64}"),
            new Rule(ProductPatentsViewSourceInventory.class,"MD5","md5:[0-9a-f]{32}"));
    private static Pattern actual(Rule rule) throws Exception {
        var field = rule.type().getDeclaredField(rule.field()); field.setAccessible(true);
        assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
        return (Pattern) field.get(null);
    }
    private static List<String> inputs() {
        var values = new ArrayList<>(List.of("", "a", "/", "..", "a/../b", "a\\b", "a:b", "0", "１２", "١", "汉", "a\n", "md5:"));
        for (int n : List.of(1,31,32,33,63,64,65,399,400,401,499,500,501))
            for (String c : List.of("a","0","F","/","汉")) { values.add(c.repeat(n)); values.add("md5:"+c.repeat(n)); }
        var random = new Random(586);
        String alphabet="aA09fFzZ._/-:\\\t\r\n汉";
        for (int n=0;n<500;n++) {
            var value=new StringBuilder(); int length=random.nextInt(520);
            for (int i=0;i<length;i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            values.add(value.toString());
        }
        return List.copyOf(values);
    }
    @Test void allActualPatternsKeepOriginalFlagsAndCompleteMatchGrammar() throws Exception {
        for (Rule rule : RULES) {
            Pattern pattern=actual(rule);
            assertThat(pattern.pattern()).isEqualTo(rule.original()); assertThat(pattern.flags()).isZero();
            for (String input : inputs()) assertThat(pattern.matcher(input).matches()).isEqualTo(input.matches(rule.original()));
        }
    }
    @Test void concurrentCallsUseIndependentMatcherState() throws Exception {
        try (var executor=Executors.newFixedThreadPool(10)) {
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for (Rule rule : RULES) {
                Pattern pattern=actual(rule);
                for (int worker=0;worker<10;worker++) jobs.add(executor.submit(() -> {
                    for (String input : inputs()) assertThat(pattern.matcher(input).matches()).isEqualTo(input.matches(rule.original()));
                }));
            }
            for (var job : jobs) job.get();
        }
    }
}
