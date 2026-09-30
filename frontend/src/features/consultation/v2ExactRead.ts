import { v2Request } from "./v2DevApi";
import errorText from "./v2DevErrorText";

export async function exactRead<T>(auth: string, path: string): Promise<{ value?: T; error?: string }> {
  try { return { value: await v2Request<T>(auth, path) }; }
  catch (failure) { return { error: errorText(failure) }; }
}
