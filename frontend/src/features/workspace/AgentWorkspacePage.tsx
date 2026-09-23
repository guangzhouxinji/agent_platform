import { Tag, Typography } from "antd";
import { ArrowRight, BookOpen, BriefcaseBusiness, Database, MessageSquareText, Scale, ShieldCheck } from "lucide-react";
import { Link } from "react-router-dom";

const agents = [
  {
    id: "consultation", name: "咨询智能体", href: "/consultation", tone: "blue",
    description: "面向知识产权问题的咨询、检索准备、候选核对与来源查看。",
    features: ["智能咨询", "咨询流程", "意图测试", "模型配置"],
    icon: <MessageSquareText size={24} aria-hidden="true" />
  },
  {
    id: "enforcement", name: "执法智能体", href: "/enforcement", tone: "green",
    description: "围绕本地来源完成线索发现、解析、人工核对和证据材料管理。",
    features: ["数据源", "线索规则", "线索发现", "证据材料"],
    icon: <ShieldCheck size={24} aria-hidden="true" />
  },
  {
    id: "adjudication", name: "裁决智能体", href: "/adjudication", tone: "amber",
    description: "集中处理案件材料、法规候选、辅助草稿和人工测试复核。",
    features: ["案件材料", "法规候选", "辅助草稿", "人工复核"],
    icon: <BriefcaseBusiness size={24} aria-hidden="true" />
  }
] as const;

export default function AgentWorkspacePage() {
  return (
    <main className="agent-hub">
      <header className="agent-hub-header">
        <div>
          <span className="agent-hub-eyebrow">知识产权智能服务</span>
          <Typography.Title level={1}>智能体工作台</Typography.Title>
          <Typography.Paragraph>选择一个智能体进入对应业务空间，各智能体的页面、状态和操作相互分开。</Typography.Paragraph>
        </div>
        <Tag className="agent-hub-status" color="green">本地服务</Tag>
      </header>

      <section className="agent-hub-section" aria-labelledby="agent-section-title">
        <div className="agent-hub-section-heading">
          <Typography.Title id="agent-section-title" level={2}>业务智能体</Typography.Title>
          <Typography.Text type="secondary">三个独立工作空间</Typography.Text>
        </div>
        <div className="agent-grid">
          {agents.map((agent) => (
            <article className={`agent-entry agent-entry-${agent.tone}`} key={agent.id}>
              <div className="agent-entry-heading">
                <span className="agent-entry-icon">{agent.icon}</span>
                <div>
                  <Typography.Title level={3}>{agent.name}</Typography.Title>
                  <Typography.Paragraph>{agent.description}</Typography.Paragraph>
                </div>
              </div>
              <ul className="agent-feature-list">
                {agent.features.map((feature) => <li key={feature}>{feature}</li>)}
              </ul>
              <Link className="agent-entry-link" to={agent.href} aria-label={`进入${agent.name}`}>
                进入工作空间 <ArrowRight size={17} aria-hidden="true" />
              </Link>
            </article>
          ))}
        </div>
      </section>

      <section className="resource-entry" aria-labelledby="resource-section-title">
        <div className="resource-entry-copy">
          <span className="resource-entry-icon"><Database size={23} aria-hidden="true" /></span>
          <div>
            <Typography.Title id="resource-section-title" level={2}>公共资料</Typography.Title>
            <Typography.Paragraph>统一访问专利、商标和案例的检索、对照与资料库。</Typography.Paragraph>
          </div>
        </div>
        <div className="resource-entry-actions">
          <Link className="resource-action" to="/search/patents"><Scale size={16} aria-hidden="true" />检索与对照</Link>
          <Link className="resource-action resource-action-primary" to="/libraries/patents"><BookOpen size={16} aria-hidden="true" />打开资料库</Link>
        </div>
      </section>
    </main>
  );
}
