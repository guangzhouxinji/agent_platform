import { evaluateConsultationSuite, readPinnedConsultationArtifact } from './consultation-task-quality.mjs';

export const RETAINED_TITLE_ARTIFACTS = Object.freeze({
  "corpus": {
    "path": "docs/evidence/consultation-agent-native-20261006/approved-run/input-title-readback.json",
    "sha256": "8a8038febc14fddf4758ecf89906de79acc5a1e7f0b23da18b6c4cba27c54117"
  },
  "checkedCitations": {
    "path": "docs/evidence/consultation-agent-window03-20261006/task-result-review.json",
    "sha256": "fa924af663daafedbb7a14aad93dbdb71e9b74ee4a0c41f86f67ee4c145e3468"
  },
  "inheritance": {
    "path": "docs/evidence/consultation-agent-browser-window04-20261006/citation-evidence-inheritance.json",
    "sha256": "573c98d57d76968d29383a73d34dbeacd85431be478a42c327db86a384da2d1a"
  }
});

for (const receipt of Object.values(RETAINED_TITLE_ARTIFACTS)) Object.freeze(receipt);

const MANIFEST = 'governance/evaluations/consultation-task-quality-v1.json';
const QUESTION_REQUESTS = [
  { id: 'RELEVANCE', question: '请在具名专业方法及任务目的下判断候选相关性；词汇命中和排序不是专业标签。空结果请明确能够判断的范围及缺失依据。', referenceKeys: ['goal', 'input', 'candidates', 'sources'] },
  { id: 'COVERAGE', question: '当前仅获准40篇母集、TITLE字段及本页结果是否足以满足任务目的？请限定结论适用范围，必要时指出需补充的样本而不是推导全球无相关资料。', referenceKeys: ['goal', 'sources', 'result', 'sourceUse'] },
  { id: 'ANSWER_ACCURACY', question: '请审查回答中的事实、数量、排序与辅助性质是否符合所给结果，指出具体不支持的陈述及其原件坐标；自然语言准确性不由结构检查证明。', referenceKeys: ['answer', 'result', 'candidates'] },
  { id: 'CITATION_SUPPORT', question: '请判断所引用材料是否实质支持回答中的具体陈述；引用坐标一致不等于语义支持。TITLE以外内容不能从本切片推断。', referenceKeys: ['answer', 'answerCitations', 'candidates', 'sourceUse'] },
  { id: 'PROFESSIONAL_COMPLETION', question: '在责任方批准的完成/转人工标准下，该任务达到什么出口？请区分Runtime停止、辅助任务完成、需人工补证与正式业务办结。', referenceKeys: ['goal', 'answer', 'result', 'run'] },
];

/** Immutable reference index, not a score sheet, approval intake, or product review workflow. */
export function buildConsultationReviewPacket(root) {
  const report = evaluateConsultationSuite(root, MANIFEST);
  if (!report.mechanicalPassed) throw Error('MECHANICAL_EVIDENCE_NOT_READY');
  const manifestReceipt = report.inputFiles.find(f => f.path === MANIFEST);
  const manifest = readPinnedConsultationArtifact(root, MANIFEST, manifestReceipt.sha256);
  const titles = Object.fromEntries(Object.entries(RETAINED_TITLE_ARTIFACTS).map(([role, file]) =>
    [role, readPinnedConsultationArtifact(root, file.path, file.sha256)]));
  if (!Array.isArray(titles.corpus.records) || titles.corpus.records.length !== 40 ||
      new Set(titles.corpus.records.map(r => r.documentId)).size !== 40 ||
      !titles.corpus.records.every(r => typeof r.documentId === 'string' && /^\d+$/.test(r.documentId) && typeof r.title === 'string') ||
      !Array.isArray(titles.checkedCitations.checkedCitations)) throw Error('RETAINED_TITLE_CORPUS_REQUIRED');
  const titleCorpus = new Map(titles.corpus.records.map(r => ['US_GRANT:' + r.documentId, r.title]));
  const titleRef = (role, pointer) => ({ ...RETAINED_TITLE_ARTIFACTS[role], pointer });
  const cases = manifest.cases.map((sample, index) => {
    // Recheck every used artifact after evaluation. A previous green result is not a read permit.
    const docs = Object.fromEntries(Object.entries(sample.artifacts).map(([role, file]) =>
      [role, readPinnedConsultationArtifact(root, file.path, file.sha256)]));
    const w = docs.workspace, checkpoint = docs.run.checkpoint;
    const turnIndex = w.dialogue.findIndex(t => 'answer-turn:' + t.turnId === checkpoint.outcome.reference);
    if (turnIndex < 0) throw Error('ANSWER_REFERENCE_REQUIRED');
    const turn = w.dialogue[turnIndex];
    const ref = (role, pointer) => ({ ...sample.artifacts[role], pointer });
    const references = {
      goal: ref('workspace', '/goal'), input: ref('workspace', '/goal/inputId'),
      task: ref('workspace', '/task'), sources: ref('workspace', '/sources'),
      candidates: ref('workspace', '/result/members'), result: ref('workspace', '/result'),
      answer: ref('workspace', `/dialogue/${turnIndex}/output/answer`),
      answerCitations: ref('workspace', `/dialogue/${turnIndex}/citations`),
      run: ref('run', '/checkpoint'), approval: ref('approval', ''),
      sourceUse: ref('sourceUse', ''), denialTrace: ref('denialTrace', ''),
      recoveryBefore: ref('recoveryBefore', ''), recoveryAfter: ref('recoveryAfter', ''),
    };
    const candidateIndex = w.result.members.map((member, memberIndex) => {
      const citationIndex = w.result.citations.findIndex(c => c.memberId === member.memberId);
      const sourceIndex = w.sources.findIndex(s => s.sourceId === member.sourceId);
      if (citationIndex < 0 || sourceIndex < 0) throw Error('CANDIDATE_REFERENCE_REQUIRED');
      const citation = w.result.citations[citationIndex];
      const prior = titles.checkedCitations.checkedCitations;
      const matches = prior.map((r, i) => ({ r, i })).filter(({ r }) =>
        r.documentId === member.documentId && r.rawSha === citation.rawSha);
      if (matches.length !== 1 || matches[0].r.title !== titleCorpus.get(member.documentId))
        throw Error('RETAINED_TITLE_BINDING_REQUIRED');
      const retainedTitleRef = titleRef('checkedCitations', '/checkedCitations/' + matches[0].i + '/title');
      return {
        memberId: member.memberId, documentId: member.documentId, retainedTitleRef,
        memberRef: ref('workspace', `/result/members/${memberIndex}`),
        citationRef: ref('workspace', `/result/citations/${citationIndex}`),
        sourceRef: ref('workspace', `/sources/${sourceIndex}`),
        originalLocator: { ref: citation.locatorRef, rawSha: citation.rawSha, readByThisCommand: false },
      };
    });
    return {
      id: sample.id, sampleKind: sample.scenario,
      taskId: w.task.id, goalId: w.goal.goalId, inputId: w.goal.inputId,
      runId: checkpoint.runId, resultSetId: w.result.resultSetId, resultSha: w.result.resultSha,
      turnId: turn.turnId, counts: { ...report.cases[index].counts },
      requestedFields: [...w.goal.requestedFields], titleCorpusRef: titleRef('corpus', '/records'), references, candidateIndex,
      questions: QUESTION_REQUESTS.map(q => ({ ...q, referenceKeys: [...q.referenceKeys] })),
      limits: ['历史证据，不产生当前运行授权', '仅获准母集及本页，不证明全语料覆盖', '专业预期及人工成本未提供', '原文定位仅索引，本命令不重新读取原文或联网核验'],
    };
  });
  return {
    schema: 'CONSULTATION_PROFESSIONAL_REVIEW_INDEX/v1', scope: report.scope,
    denominator: report.denominator, mechanicalPassed: true, professionalAccepted: false,
    overallAcceptance: 'INCOMPLETE', currentAuthorization: 'NOT_EVALUATED',
    manifest: { ...manifestReceipt },
    retainedTitleReferences: {
      corpus: titleRef('corpus', '/records'), checkedCitations: titleRef('checkedCitations', '/checkedCitations'),
      inheritance: titleRef('inheritance', ''),
      limits: '仅已留存TITLE核验快照；旧Task成功不是本任务验收，也不是专家标签；未重读data/原文。',
    },
    professionalContract: {
      rubricVersion: null, rubricEvidence: null, reviewerEvidence: null, professionalLabels: null,
      decisionRef: 'Q-CON-QUALITY-01',
      requiresEvidence: ['责任人及其职责依据', '原始记录/来源与版本', '决定日期和适用范围', '样本分母及专业预期/依据', '相关性/准确性/引用支持判定方法', '完成/转人工条件及通过标准'],
    },
    unobservedCoverage: [...report.unobservedCoverage], cases,
  };
}
