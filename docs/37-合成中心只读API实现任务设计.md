# 合成中心只读 API 实现任务设计

任务：CON-CENTER-SYNTH-API  
日期：2026-09-16  
前置：`CON-04-SYNTH-VERIFY`、`CON-05-SYNTH-VERIFY`，以及开发期统一使用合成数据的项目决定。

## 1. 边界

本任务将现有、版本化的合成专利和商标目录以开发环境 HTTP 资源公开，供页面、适配器契约测试和后续真实中心替换准备使用。所有响应必须包含 `SYNTHETIC_V0`、目录版本和限制说明。

不实现中心认证、真实权限、中心写回、数据同步、缓存、增量、文件下载、模型调用、法律/专业结论或正式状态。现有咨询领域服务继续通过 `PatentCatalogPort`、`TrademarkCatalogPort` 使用目录，不能反向 HTTP 调用本服务。

## 2. 最小契约

| GET 资源 | 结果 | 异常 |
|---|---|---|
| `/api/v0/synthetic-center` | 合成环境元数据、数据模式与两个目录版本 | 无 |
| `/api/v0/synthetic-center/patents?query=&type=&region=` | 合成专利列表；仅按标题、摘要、权利要求、标签及已有类型/地域字段过滤 | 参数非法为 `400` |
| `/api/v0/synthetic-center/patents/{sourceId}` | 一条合成专利及版本 | 不存在为 `404` |
| `/api/v0/synthetic-center/trademarks?query=&category=&status=` | 合成商标列表；仅按名称/读音、类别和状态过滤 | 参数非法为 `400` |
| `/api/v0/synthetic-center/trademarks/{sourceId}` | 一条合成商标及版本 | 不存在为 `404` |

接口仅注册 GET 映射。稳定源标识、版本和数据模式在每项记录中返回；响应不承诺 Recall@K、全量覆盖或真实中心兼容。

## 3. 验收与 D 门

自动化 HTTP 测试必须证明：元数据显式标记合成；专利和商标能按已声明字段检索；详情保留稳定 ID/版本；不存在的资源返回 404；非 GET 方法没有写入路由。测试只使用现有虚构目录，不新增业务模型或持久化。

`P0-01/03/04` 的 V0 决策允许该可替换开发适配层。结论：**本任务 D 门通过，仅准许合成中心只读 API 的实现与测试。** 不升级任何 `CON-*` 需求的真实 D/V/A，也不视为中心 API 联调。
