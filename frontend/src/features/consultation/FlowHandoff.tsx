import { useEffect, useMemo } from "react";
import { Alert, Button } from "antd";
import { ArrowLeft } from "lucide-react";
import { loadConsultationFlow, recordConsultationFlowStep, type ConsultationFlowStep } from "./consultationFlowState";

export function FlowHandoff({ flowId, step, status, reference }: {
  flowId?: string | null;
  step?: ConsultationFlowStep;
  status?: string;
  reference?: string;
}) {
  const flow = useMemo(() => loadConsultationFlow(flowId), [flowId]);

  useEffect(() => {
    if (flowId && step && status) recordConsultationFlowStep(flowId, { step, status, reference });
  }, [flowId, reference, status, step]);

  if (!flowId || !flow) return null;
  return <Alert className="flow-handoff" type="info" showIcon
    title="来自内部流程调试"
    description={<span>当前问题：{flow.question}</span>}
    action={<Button href={`/app/consultation/flow?flowId=${encodeURIComponent(flowId)}`} icon={<ArrowLeft size={15} aria-hidden="true" />}>返回流程调试</Button>}
  />;
}
