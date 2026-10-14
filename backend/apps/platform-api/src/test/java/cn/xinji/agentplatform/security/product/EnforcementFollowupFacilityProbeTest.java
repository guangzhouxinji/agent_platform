package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Infrastructure connectivity evidence only; not a substitute for any product I. */
@EnabledIfEnvironmentVariable(named = "ENF_FOLLOWUP_FACILITY_PROBE", matches = "true")
class EnforcementFollowupFacilityProbeTest {
    @Test void threeDedicatedDm8EndpointsExecuteActualJdbcQuery() throws Exception {
        String[][] endpoints = {
            {"5311", "ENFACC_BIZ", required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")},
            {"5312", "ENFACC_ASVC", required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")},
            {"5319", "ENFACC_BIZ", required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")}
        };
        for (String[] endpoint : endpoints) {
            String url = "jdbc:dm://127.0.0.1:" + endpoint[0];
            try (var connection = DriverManager.getConnection(url, endpoint[1], endpoint[2]);
                 var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1 FROM DUAL")) {
                if (!result.next()) throw new IllegalStateException("No actual DM8 readiness result");
                assertEquals(1, result.getInt(1));
                System.out.println("ENF_FACILITY_DM8_READY port=" + endpoint[0]);
            }
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
}

