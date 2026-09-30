import { Button, Tag, Typography } from "antd";
import { ClipboardCheck, UserCheck } from "lucide-react";

export type CollectionTask = { taskId: string; candidateId: string; packageId: string; title: string;
  assignmentState: "UNCLAIMED" | "CLAIMED"; workflowState: string; claimedBy?: string;
  organization?: string; claimedAt?: string; publicInput: { sourceName: string; sourceUrl: string; rawSha256: string } };

type Props = { tasks: CollectionTask[]; busy: boolean; organizations: Record<string, string>;
  onClaim: (task: CollectionTask) => void; onOpen: (task: CollectionTask) => void };

export default function EnforcementCollectionTaskQueue({ tasks, busy, organizations, onClaim, onOpen }: Props) {
  return <section className="enf-job-pack-queue" aria-labelledby="collection-task-title">
    <div className="enf-job-pack-queue-heading"><div>
      <Typography.Title id="collection-task-title" level={3}>真实业务采集待办</Typography.Title>
      <span>{tasks.length} 项公开任务待实际人员处理</span></div>
      <ClipboardCheck size={22} aria-hidden="true" /></div>
    <ul>{tasks.map(task => <li key={task.taskId}>
      <div className="enf-job-pack-queue-main"><div className="enf-job-pack-queue-title">
        <Tag color={task.assignmentState === "CLAIMED" ? "blue" : "gold"}>
          {task.assignmentState === "CLAIMED" ? "已领取" : "待领取"}</Tag>
        <strong>{task.title}</strong></div>
        <dl><div><dt>任务</dt><dd>{task.taskId}</dd></div><div><dt>来源</dt><dd>{task.publicInput.sourceName}</dd></div>
          <div><dt>状态</dt><dd>{task.workflowState}</dd></div><div><dt>原件 SHA</dt><dd>{task.publicInput.rawSha256}</dd></div></dl>
      </div>
      {task.assignmentState === "UNCLAIMED" ? <div className="enf-job-pack-queue-action">
        <span>当前授权组织</span>
        <strong>{organizations[task.packageId] || "当前账号没有可用领取授权"}</strong>
        <Button type="primary" icon={<UserCheck size={16} aria-hidden="true" />} loading={busy}
          disabled={!organizations[task.packageId]} onClick={() => onClaim(task)}>领取任务</Button>
      </div> : <div className="enf-job-pack-queue-assignee"><span>当前操作者</span><strong>{task.claimedBy}</strong>
        <span>{task.organization}</span><Button onClick={() => onOpen(task)}>打开任务</Button></div>}
    </li>)}</ul>
  </section>;
}
