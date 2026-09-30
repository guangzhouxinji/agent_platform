package cn.xinji.agentplatform.enforcement.evidence;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class EvidenceBrowserE2e {
    private EvidenceBrowserE2e() { }

    interface CheckedAction { void run() throws Exception; }

    static void run(String base, CheckedAction withdrawal) throws Exception {
        if (!"1".equals(System.getenv("ENF_BROWSER_E2E"))) {
            withdrawal.run();
            return;
        }
        Process browser = new ProcessBuilder("node", "frontend/e2e/enforcement-evidence-v2-product.mjs",
                base).redirectErrorStream(true).start();
        try (var output = new BufferedReader(new InputStreamReader(browser.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null && !line.equals("ENF_EVIDENCE_BROWSER_READY"))
                System.out.println(line);
            if (line == null) throw new IllegalStateException("Evidence browser did not read the V2 organization");
            withdrawal.run();
            browser.getOutputStream().write('\n');
            browser.getOutputStream().flush();
            browser.getOutputStream().close();
            boolean cleared = false;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                cleared |= line.equals("ENF_EVIDENCE_BROWSER_WITHDRAWAL_CLEARED");
            }
            if (!browser.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)
                    || browser.exitValue() != 0 || !cleared)
                throw new IllegalStateException("Evidence browser withdrawal verification failed");
        } finally {
            if (browser.isAlive()) browser.destroyForcibly();
        }
    }
}
