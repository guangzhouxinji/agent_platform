package cn.xinji.agentplatform.adjudication.casework;

/** Explicit verification against two independent DM8 instances, using exclusively randomly owned schemas. */
class AdjudicationAgentTaskDm8Verification extends AdjudicationAgentTaskHttpTest {
    @Override CaseApiFixture fixture() throws Exception {return new CaseApiFixture(true);}
}
