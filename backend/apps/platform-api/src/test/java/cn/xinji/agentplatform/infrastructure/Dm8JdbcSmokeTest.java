package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Dm8JdbcSmokeTest {
    @Test
    void verifiesLocalDm8ConnectionTransactionAndChineseLongText() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            dropIfExists(statement);
            statement.execute("CREATE TABLE spk_jdbc_v0 (id INT PRIMARY KEY, note VARCHAR(2000))");
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO spk_jdbc_v0 VALUES (1, '咨询MVP中文长文本验证')");
            connection.rollback();
            assertEquals(0, count(statement));

            String longText = "知识产权咨询来源追溯。".repeat(40);
            statement.executeUpdate("INSERT INTO spk_jdbc_v0 VALUES (2, '" + longText + "')");
            connection.commit();
            assertEquals(1, count(statement));
            try (ResultSet result = statement.executeQuery("SELECT note FROM spk_jdbc_v0 WHERE id = 2")) {
                assertTrue(result.next());
                assertEquals(longText, result.getString(1));
            }
            connection.setAutoCommit(true);
            statement.execute("DROP TABLE spk_jdbc_v0");
        }
    }

    private static int count(Statement statement) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM spk_jdbc_v0")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void dropIfExists(Statement statement) {
        try { statement.execute("DROP TABLE spk_jdbc_v0"); } catch (Exception ignored) { }
    }
}
