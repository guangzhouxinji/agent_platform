package cn.xinji.agentplatform.persistence.authority;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AuthorityDataSourceSettingsTest {
    @ParameterizedTest
    @NullSource @EmptySource @ValueSource(strings = {"independent", " "})
    void defaultIndependentAllowsSameSchemaAndUsernameInDifferentDatabases(String topology) {
        var settings = AuthorityDataSourceSettings.resolve(topology, "jdbc:primary", "PUBLIC", "sa",
                "jdbc:authority", "public", "sa", "");
        assertEquals(AuthorityDataSourceSettings.Topology.INDEPENDENT, settings.topology());
        assertEquals("jdbc:authority", settings.jdbcUrl());
        assertEquals("PUBLIC", settings.schema());
        assertEquals("sa", settings.username());
        assertEquals("", settings.password());
    }

    @Test void independentRetainsLegacyRulesWithoutRequiringPrimarySchemaOrUsername() {
        var settings = AuthorityDataSourceSettings.resolve("independent", null, null, null,
                "jdbc:authority", "Authority", "legacy-user", null);
        assertEquals("AUTHORITY", settings.schema());
        assertEquals("legacy-user", settings.username());
        assertNull(settings.password());
    }

    @ParameterizedTest
    @NullSource @EmptySource @ValueSource(strings = {" "})
    void sharedInstanceInheritsOnlyMissingAuthorityUrl(String authorityUrl) {
        var settings = AuthorityDataSourceSettings.resolve("shared-instance", "jdbc:primary", "BUSINESS", "biz_user",
                authorityUrl, "Authority", "auth_user", "opaque password ");
        assertEquals(AuthorityDataSourceSettings.Topology.SHARED_INSTANCE, settings.topology());
        assertEquals("jdbc:primary", settings.jdbcUrl());
        assertEquals("AUTHORITY", settings.schema());
        assertEquals("auth_user", settings.username());
        assertEquals("opaque password ", settings.password());
    }

    @Test void sharedInstanceAcceptsExactlyMatchingExplicitUrlAndSingleLetterUsernames() {
        var settings = AuthorityDataSourceSettings.resolve("shared-instance", "jdbc:primary", "BIZ", "b",
                "jdbc:primary", "AUTH", "a", "");
        assertEquals("jdbc:primary", settings.jdbcUrl());
    }

    @ParameterizedTest(name = "invalid shared configuration {index}")
    @MethodSource("invalidSharedConfigurations")
    void invalidSharedConfigurationIsRejectedWithoutExposingInputs(String[] values) {
        var failure = assertThrows(IllegalArgumentException.class, () -> resolve(values));
        assertFalse(failure.getMessage().contains("jdbc:"));
        assertFalse(failure.getMessage().contains("secret-marker"));
        assertNull(failure.getCause());
    }

    static Stream<Arguments> invalidSharedConfigurations() {
        List<String[]> cases = new ArrayList<>();
        String[] valid = {"shared-instance", "jdbc:primary", "BUSINESS", "biz_user", "",
                "AUTHORITY", "auth_user", "secret-marker"};
        add(cases, valid, 0, "unknown", "SHARED_INSTANCE", " shared-instance ");
        add(cases, valid, 1, null, "", " ");
        add(cases, valid, 2, null, "", " ", "ab", "bad;schema", "a".repeat(65), " authority ", "authority");
        add(cases, valid, 3, null, "", " ", "bad;user", "1user", "a".repeat(65), "AUTH_USER", "auth_user");
        add(cases, valid, 4, "jdbc:other", "jdbc:primary ");
        add(cases, valid, 5, null, "", " ", "ab", "bad;schema", "a".repeat(65), "business", "BUSINESS");
        add(cases, valid, 6, null, "", " ", "bad;user", "1user", "a".repeat(65), "BIZ_USER", "biz_user");
        return cases.stream().map(values -> Arguments.of((Object) values));
    }

    @ParameterizedTest(name = "invalid independent configuration {index}")
    @MethodSource("invalidIndependentConfigurations")
    void invalidIndependentConfigurationRemainsRejected(String[] values) {
        assertThrows(IllegalArgumentException.class, () -> resolve(values));
    }

    static Stream<Arguments> invalidIndependentConfigurations() {
        List<String[]> cases = new ArrayList<>();
        String[] valid = {"independent", "jdbc:primary", null, null, "jdbc:authority", "PUBLIC", "sa", ""};
        add(cases, valid, 0, "off", "shared", "INDEPENDENT");
        add(cases, valid, 4, null, "", " ", "jdbc:primary");
        add(cases, valid, 5, null, "", "ab", "bad;schema", "a".repeat(65));
        add(cases, valid, 6, null, "", " ");
        return cases.stream().map(values -> Arguments.of((Object) values));
    }

    @Test void diagnosticRenderingDoesNotExposeConnectionOrCredentials() {
        var settings = AuthorityDataSourceSettings.resolve(null, "jdbc:primary", null, null,
                "jdbc:authority?password=url-secret", "PUBLIC", "private_user", "password-secret");
        assertEquals("AuthorityDataSourceSettings[topology=INDEPENDENT]", settings.toString());
    }

    private static void add(List<String[]> cases, String[] valid, int field, String... replacements) {
        for (String value : replacements) {
            String[] changed = valid.clone();
            changed[field] = value;
            cases.add(changed);
        }
    }

    private static AuthorityDataSourceSettings resolve(String[] values) {
        return AuthorityDataSourceSettings.resolve(values[0], values[1], values[2], values[3],
                values[4], values[5], values[6], values[7]);
    }
}
