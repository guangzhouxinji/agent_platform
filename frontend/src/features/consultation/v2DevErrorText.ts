import { V2DevError } from "./v2DevApi";

export default function errorText(error: unknown) {
  if (!(error instanceof V2DevError)) return "连接失败，请核对本地服务并重试。";
  const known: Record<string, string> = {
    TASK_NOT_FOUND: "任务不存在或当前身份不可访问。", TASK_EXPIRED: "任务已到期，不能读取或继续。",
    TASK_CLOSED: "任务已取消，不能继续提交。", ACCESS_DENIED: "当前身份无权执行此操作。",
    SOURCE_UNAVAILABLE: "来源目前不可用，未展示原文。", FEATURE_SOURCE_UNAVAILABLE: "特征来源目前不可用。",
    VERSION_CONFLICT: "任务版本已变化，请刷新后再提交。", RESULT_VERSION_CONFLICT: "结果序号已变化，请刷新。",
    FOCUS_VERSION_CONFLICT: "焦点序号已变化，请刷新。", FEATURE_VERSION_CONFLICT: "确认序号已变化，请刷新。",
    WORK_STATE_INCONSISTENT: "工作引用不一致，服务已拒绝返回。", DATABASE_UNAVAILABLE: "任务服务暂时不可用。",
    READ_SNAPSHOT_CHANGED: "任务状态已变化，请刷新核查。",
    HUMAN_REQUEST_SOURCE_UNAVAILABLE: "人工请求来源目前不可用，未展示请求文字。",
    HUMAN_REQUEST_VERSION_CONFLICT: "人工请求序号已变化，请刷新。"
  };
  return known[error.code] ?? `请求未完成（${error.status} · ${error.code}）。`;
}
