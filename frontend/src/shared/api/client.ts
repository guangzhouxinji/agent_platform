export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly requestId?: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ApiRequestOptions extends RequestInit {
  actor?: string;
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  headers.set("X-Request-Id", crypto.randomUUID());
  if (options.actor) headers.set("X-V0-Actor", options.actor);
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  const response = await fetch(path, { ...options, headers });
  const body = await response.json().catch(() => null) as Record<string, any> | null;
  if (!response.ok) {
    const nested = body?.error && typeof body.error === "object" ? body.error : undefined;
    const fallbackMessages: Record<number, string> = {
      400: "提交内容不完整，请检查后重试",
      401: "当前身份验证失败，请重新进入系统后重试",
      403: "当前账号无权执行此操作",
      404: "请求的资料不存在、已失效或当前不可访问",
      405: "当前服务版本不支持此操作，请刷新服务后重试",
      409: "内容已发生变化，请刷新后重试",
      429: "请求过于频繁，请稍后重试",
      500: "服务处理失败，请稍后重试",
      503: "服务暂时不可用，请检查运行配置后重试"
    };
    throw new ApiError(
      typeof nested?.message === "string" ? nested.message : typeof body?.message === "string" ? body.message : fallbackMessages[response.status] ?? `请求失败 (${response.status})`,
      response.status,
      typeof nested?.code === "string" ? nested.code : typeof body?.code === "string" ? body.code : undefined,
      typeof body?.requestId === "string" ? body.requestId : response.headers.get("X-Request-Id") ?? undefined
    );
  }
  return body as T;
}
