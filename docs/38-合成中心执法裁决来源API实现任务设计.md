# 合成中心执法与裁决来源 API 实现任务设计

任务：CENTER-SYNTH-ENF-ADJ-API  
日期：2026-09-16

本任务仅将既有执法、裁决 V0 源适配器所使用的合成数据公开为开发期 GET 资源。执法资源保留线索和证据的独立语义；裁决资源保留案件、材料、法规候选和模板的独立语义。不得统一其数据模型，不得提供写入、正式状态、真实身份、数据同步或专业判断。

资源为：`/api/v0/synthetic-center/enforcement/clues`、`.../evidence`、`/api/v0/synthetic-center/adjudication/cases`、`.../materials`、`.../legal-candidates`、`.../template`，以及各线索、证据、案件和材料的详情 GET。每个响应带 `SYNTHETIC_V0`、目录版本和限制说明；详情缺失返回 404。

验收：HTTP 自动测试覆盖各领域资源、稳定来源/版本、详情缺失和无写路由；现有执法、裁决工作台回归不受影响。结论：本任务 D 门仅准许上述合成开发资源实现，不升级真实中心接入、数据授权、专业规则或业务验收。
