package cn.xinji.agentplatform.enforcement.procedure;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class ProcedureBrowserE2e {
    private ProcedureBrowserE2e() { }
    interface CheckedAction { void run() throws Exception; }

    static void run(String base, String username, String password, String ruleId,
                    String reportId, CheckedAction withdrawal) throws Exception {
        if (!"1".equals(System.getenv("ENF_BROWSER_E2E"))) return;
        var browser = new ProcessBuilder("node", "frontend/e2e/enforcement-next-v2-product.mjs",
                base, "procedure", username, password, ruleId, "2", reportId, "3")
                .redirectErrorStream(true).start();
        try (var output = new BufferedReader(new InputStreamReader(browser.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean read = false;
            while ((line = output.readLine()) != null && !line.equals("ENF_PROCEDURE_BROWSER_READY")) {
                read |= line.equals("ENF_PROCEDURE_BROWSER_OK");
                System.out.println(line);
            }
            if (!read || line == null) throw new IllegalStateException("Procedure browser did not read V2 report");
            withdrawal.run();
            browser.getOutputStream().write('\n');
            browser.getOutputStream().flush();
            browser.getOutputStream().close();
            boolean cleared = false;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                cleared |= line.equals("ENF_PROCEDURE_BROWSER_WITHDRAWAL_CLEARED");
            }
            if (!browser.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS) ||
                    browser.exitValue() != 0 || !cleared)
                throw new IllegalStateException("Procedure browser withdrawal verification failed");
        } finally {
            if (browser.isAlive()) browser.destroyForcibly();
        }
    }
}
