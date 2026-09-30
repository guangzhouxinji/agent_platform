import { taskPath, v2Request, V2DevError,
  type HumanOpinion, type HumanRequestCurrent } from "./v2DevApi";
import errorText from "./v2DevErrorText";

export async function loadHumanOpinion(auth: string, id: string, human?: HumanRequestCurrent): Promise<{
  opinion?: HumanOpinion; opinionError?: string; opinionMissing?: boolean }> {
  const request = human?.request;
  if (request?.acceptanceState !== "ACCEPTED") return {};
  try {
    return { opinion: await v2Request<HumanOpinion>(auth,
      taskPath(id) + "/human-requests/" + request.requestSequence + "/opinion") };
  } catch (failure) {
    if (failure instanceof V2DevError && failure.status === 404) return { opinionMissing: true };
    return { opinionError: errorText(failure) };
  }
}
