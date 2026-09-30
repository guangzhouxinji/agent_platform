package cn.xinji.agentplatform.enforcement.material;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class MaterialBrowserE2e {
    private MaterialBrowserE2e() { }

    interface CheckedAction { void run() throws Exception; }

    static void run(String base, String materialId, CheckedAction withdrawal) throws Exception {
        if (!"1".equals(System.getenv("ENF_BROWSER_E2E"))) {
            withdrawal.run();
            return;
        }
        Process browser = new ProcessBuilder("node", "frontend/e2e/enforcement-material-v2-product.mjs",
                base, materialId).redirectErrorStream(true).start();
        try (var output = new BufferedReader(new InputStreamReader(browser.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null && !line.equals("ENF_BROWSER_READY"))
                System.out.println(line);
            if (line == null) throw new IllegalStateException("Browser did not reach the product material");
            withdrawal.run();
            browser.getOutputStream().write('\n');
            browser.getOutputStream().flush();
            browser.getOutputStream().close();
            boolean cleared = false;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                cleared |= line.equals("ENF_BROWSER_WITHDRAWAL_CLEARED");
            }
            if (!browser.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)
                    || browser.exitValue() != 0 || !cleared)
                throw new IllegalStateException("Product browser withdrawal verification failed");
        } finally {
            if (browser.isAlive()) browser.destroyForcibly();
        }
    }
}
