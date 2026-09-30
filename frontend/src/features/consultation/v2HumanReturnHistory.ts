import { taskPath, v2Request, V2DevError,
  type HumanReturn, type HumanRequestCurrent } from "./v2DevApi";
import errorText from "./v2DevErrorText";

export async function loadHumanReturn(auth: string, id: string, human?: HumanRequestCurrent): Promise<{
  returned?: HumanReturn; returnError?: string; returnMissing?: boolean }> {
  const request = human?.request;
  if (request?.acceptanceState !== "ACCEPTED") return {};
  try {
    return { returned: await v2Request<HumanReturn>(auth,
      taskPath(id) + "/human-requests/" + request.requestSequence + "/return") };
  } catch (failure) {
    if (failure instanceof V2DevError && failure.status === 404) return { returnMissing: true };
    return { returnError: errorText(failure) };
  }
}
