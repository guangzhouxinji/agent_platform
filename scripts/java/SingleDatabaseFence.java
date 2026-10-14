import java.io.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Persistent maintenance connection. No credentials, rows, or driver diagnostics on stdout. */
public final class SingleDatabaseFence {
    static String identifier(String v) {
        if (v == null || !v.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException();
        return v;
    }
    static SortedSet<String> tables(Connection c, List<String> schemas) throws Exception {
        SortedSet<String> result = new TreeSet<>();
        try (PreparedStatement p = c.prepareStatement("SELECT TABLE_NAME FROM DBA_TABLES WHERE OWNER=? ORDER BY TABLE_NAME")) {
            p.setQueryTimeout(10);
            for (String schema : schemas) {
                p.setString(1, schema);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        result.add("\"" + schema + "\".\"" + identifier(r.getString(1)) + "\"");
                        if (result.size() > 4096) throw new IllegalStateException();
                    }
                }
            }
        }
        if (result.isEmpty()) throw new IllegalStateException();
        return result;
    }
    static String digest(SortedSet<String> tables) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", tables).getBytes(StandardCharsets.UTF_8)));
    }
    static void send(String code, String sha, int count) {
        System.out.println(code + " " + sha + " " + count); System.out.flush();
    }
    public static void main(String[] args) {
        Connection c = null;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String first = input.readLine();
            if (first == null || first.length() > 512) throw new IllegalArgumentException();
            List<String> schemas = Arrays.stream(first.split(",", -1)).map(SingleDatabaseFence::identifier).toList();
            if (schemas.size() != 6 || new HashSet<>(schemas).size() != 6) throw new IllegalArgumentException();
            c = DriverManager.getConnection(System.getenv("LIFECYCLE_FENCE_URL"),
                    System.getenv("LIFECYCLE_FENCE_USER"), System.getenv("LIFECYCLE_FENCE_PASSWORD"));
            if (!c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("dm")) throw new IllegalArgumentException();
            c.setAutoCommit(false);
            SortedSet<String> held = tables(c, schemas);
            for (String table : held) {
                try (Statement s = c.createStatement()) {
                    s.setQueryTimeout(10);
                    s.execute("LOCK TABLE " + table + " IN SHARE MODE");
                }
            }
            if (!held.equals(tables(c, schemas))) throw new IllegalStateException();
            String sha = digest(held); send("HELD", sha, held.size());
            String command;
            while ((command = input.readLine()) != null) {
                if (command.equals("RELEASE")) { c.rollback(); send("RELEASED", sha, held.size()); return; }
                if (!command.equals("CHECK") || !c.isValid(5) || !held.equals(tables(c, schemas))) throw new IllegalStateException();
                // No commits while held. An owner can still alter schema/permissions; the full writer
                // responsibility and stopped-container checks remain independently mandatory.
                send("HELD", sha, held.size());
            }
        } catch (Throwable failure) {
            System.out.println("FENCE_REFUSED"); System.out.flush();
        } finally {
            if (c != null) { try { c.rollback(); } catch (Exception ignored) {} try { c.close(); } catch (Exception ignored) {} }
        }
    }
}
