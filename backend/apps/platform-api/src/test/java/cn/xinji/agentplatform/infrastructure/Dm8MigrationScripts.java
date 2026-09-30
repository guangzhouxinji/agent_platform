package cn.xinji.agentplatform.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class Dm8MigrationScripts {
    private Dm8MigrationScripts() { }

    static void script(Connection connection, String name) throws Exception {
        String sql = Files.readString(WorkspaceTestPaths.root().resolve(Path.of("db", "dm8", name)));
        try (Statement statement = connection.createStatement()) {
            int index = 0;
            for (String command : sql.split(";\\s*(?:\\r?\\n|$)")) {
                if (!command.isBlank()) executeSafe(statement, command, name + " statement " + (++index));
            }
        }
    }

    static void executeSafe(Connection connection, String command, String step) throws SQLException {
        try (Statement statement = connection.createStatement()) { executeSafe(statement, command, step); }
    }

    private static void executeSafe(Statement statement, String command, String step) throws SQLException {
        try { statement.execute(command); } catch (SQLException failure) {
            throw new SQLException(step + " failed: SQLState=" + failure.getSQLState() + ", code=" + failure.getErrorCode()
                    + ", detail=" + failure.getMessage());
        }
    }
}
