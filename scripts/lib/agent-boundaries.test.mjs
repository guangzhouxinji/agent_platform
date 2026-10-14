import test from "node:test";
import assert from "node:assert/strict";
import { agentBoundaryViolations } from "./agent-boundaries.mjs";

test("three independently owned Agents can use shared Runtime but not each other's implementations", () => {
  for (const owner of ["consultation", "enforcement", "adjudication"]) {
    assert.deepEqual(agentBoundaryViolations(owner, `import cn.xinji.agentplatform.${owner}.application.Service; import cn.xinji.agentplatform.runtime.AgentRuntime;`), []);
    for (const foreign of ["consultation", "enforcement", "adjudication"].filter(domain => domain !== owner)) {
      assert.equal(agentBoundaryViolations(owner, `import cn.xinji.agentplatform.${foreign}.application.Service;`).length, 1);
      assert.equal(agentBoundaryViolations(owner, `new cn.xinji.agentplatform.${foreign}.application.Service();`).length, 1);
      assert.equal(agentBoundaryViolations(owner, "", [`agent-platform-${foreign}`]).length, 1);
    }
  }
});
test("kernel does not absorb any Agent, database, Spring or HTTP client", () => {
  for (const namespace of ["cn.xinji.agentplatform.consultation", "cn.xinji.agentplatform.enforcement", "cn.xinji.agentplatform.adjudication", "cn.xinji.agentplatform.modelconfig", "org.springframework", "java.sql", "javax.sql", "java.net.http"])
    assert.ok(agentBoundaryViolations("kernel", `import ${namespace}.Implementation;`).length);
  assert.deepEqual(agentBoundaryViolations("kernel", "import java.time.Clock; import cn.xinji.agentplatform.runtime.AgentContract;"), []);
});
