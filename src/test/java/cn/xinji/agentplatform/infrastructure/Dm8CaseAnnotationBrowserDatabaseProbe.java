package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.Test;

class Dm8CaseAnnotationBrowserDatabaseProbe {
    @Test void executeRequestedPhase() throws Exception {
        String phase = System.getProperty("case.annotation.browser.phase", "verify");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(required("DM8_JDBC_URL"), required("DM8_JDBC_USER"), required("DM8_JDBC_PASSWORD"))) {
            switch (phase) {
                case "setup" -> { runIgnoring(connection, "db/dm8/U004__case_annotation_v0.sql"); run(connection, "db/dm8/V004__case_annotation_v0.sql"); }
                case "verify" -> verify(connection);
                case "cleanup" -> runIgnoring(connection, "db/dm8/U004__case_annotation_v0.sql");
                default -> fail("unknown phase: " + phase);
            }
        }
    }

    private static void verify(Connection connection) throws SQLException {
        assertEquals(1, count(connection, "con_case_extract_task"));
        assertEquals(1, count(connection, "con_case_annotation"));
        assertEquals(4, count(connection, "con_case_annotation_version"));
        assertEquals(4, count(connection, "con_case_annotation_audit"));
        assertEquals("PUBLISHED", text(connection, "SELECT current_state FROM con_case_annotation"));
        assertEquals(3, integer(connection, "SELECT row_version FROM con_case_annotation"));
    }
    private static String required(String name) { String value=System.getenv(name); if(value==null||value.isBlank())throw new IllegalStateException(name+" is required"); return value; }
    private static int count(Connection connection,String table)throws SQLException{return integer(connection,"SELECT COUNT(*) FROM "+table);}
    private static int integer(Connection connection,String sql)throws SQLException{try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(sql)){rows.next();return rows.getInt(1);}}
    private static String text(Connection connection,String sql)throws SQLException{try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(sql)){rows.next();return rows.getString(1);}}
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
}
