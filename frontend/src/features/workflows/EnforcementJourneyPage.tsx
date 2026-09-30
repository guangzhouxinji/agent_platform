import { Alert, Tag, Typography } from "antd";
import { ArrowRight, ClipboardCheck, Database, Files, Radar, ScanSearch, Settings2, ShieldCheck } from "lucide-react";
import { Link } from "react-router-dom";
import "./EnforcementJourneyPage.css";

type JourneyStage = {
  id: string;
  number: string;
  title: string;
  description: string;
  boundary: string;
  icon: typeof Radar;
  links?: Array<{ to: string; label: string; detail: string }>;
  unavailable?: boolean;
};

const stages: JourneyStage[] = [
  {
    id: "discovery",
    number: "01",
    title: "发现问题",
    description: "从已授权的本地数据源和已发布规则中生成待核实线索。",
    boundary: "规则命中仅形成候选，不构成违法认定、立案或处罚决定。",
    icon: Radar,
    links: [
      { to: "/enforcement/discovery", label: "进入线索发现", detail: "查看待核实线索与发现任务" },
      { to: "/enforcement/rules", label: "维护线索规则", detail: "查看规则草稿、试运行与发布" }
    ]
  },
  {
    id: "verification",
    number: "02",
    title: "核实线索",
    description: "解析线索字段、查看来源与版本，并由人员修订待核实信息。",
    boundary: "不得补造主体、时间、地点、权利状态或来源内容。",
    icon: ScanSearch,
    links: [{ to: "/enforcement/parsing", label: "进入线索解析", detail: "核对字段、冲突和人工修订记录" }]
  },
  {
    id: "materials",
    number: "03",
    title: "形成材料",
    description: "登记和关联证据材料，核对来源、版本、时间线和材料缺口。",
    boundary: "材料登记不等于真实性、证明力或证据采信结论。",
    icon: Files,
    links: [{ to: "/enforcement/evidence", label: "进入证据材料", detail: "查看材料、关联、版本和来源信息" }]
  },
  {
    id: "human-disposition",
    number: "04",
    title: "人工处置 / 移交",
    description: "后续由有权人员或权威业务系统完成处置与移交。",
    boundary: "当前不提供自动立案、违法认定、处罚、签发或中心状态写回。",
    icon: ClipboardCheck,
    unavailable: true
  }
];

export default function EnforcementJourneyPage() {
  return (
    <main className="enforcement-journey" aria-labelledby="enforcement-journey-title">
      <header className="enforcement-journey-header">
        <div>
          <span className="enforcement-journey-eyebrow"><ShieldCheck size={15} aria-hidden="true" />执法辅助工作流</span>
          <Typography.Title id="enforcement-journey-title" level={1}>执法工作台</Typography.Title>
          <Typography.Paragraph>按一项线索的处理顺序进入已有工作页面，工作台只提供辅助信息和人工核对入口。</Typography.Paragraph>
        </div>
        <Tag color="green">本地资料优先</Tag>
      </header>

      <Alert
        className="enforcement-journey-notice"
        type="info"
        showIcon
        message="所有线索、解析与材料均须由人员核对；本工作台不作正式执法决定。"
      />

      <section className="enforcement-journey-flow" aria-label="执法辅助处理阶段">
        {stages.map((stage, index) => {
          const Icon = stage.icon;
          return (
            <article className={`enforcement-journey-stage${stage.unavailable ? " is-unavailable" : ""}`} key={stage.id}>
              <div className="enforcement-journey-stage-marker" aria-hidden="true">
                <span>{stage.number}</span>
                <Icon size={20} />
              </div>
              <div className="enforcement-journey-stage-copy">
                <div className="enforcement-journey-stage-heading">
                  <Typography.Title level={2}>{stage.title}</Typography.Title>
                  {stage.unavailable && <Tag>当前不可用</Tag>}
                </div>
                <Typography.Paragraph>{stage.description}</Typography.Paragraph>
                <p className="enforcement-journey-boundary">{stage.boundary}</p>
              </div>
              {stage.links && <nav className="enforcement-journey-links" aria-label={`${stage.title}入口`}>
                {stage.links.map((item) => (
                  <Link key={item.to} to={item.to} className="enforcement-journey-link">
                    <span><strong>{item.label}</strong><small>{item.detail}</small></span>
                    <ArrowRight size={17} aria-hidden="true" />
                  </Link>
                ))}
              </nav>}
              {stage.unavailable && <div className="enforcement-journey-unavailable" role="status">
                <Settings2 size={18} aria-hidden="true" />
                <span>待接入正式权限、流程和权威系统后开放。</span>
              </div>}
              {index < stages.length - 1 && <span className="enforcement-journey-connector" aria-hidden="true" />}
            </article>
          );
        })}
      </section>

      <section className="enforcement-journey-footer" aria-label="工作台边界">
        <Database size={18} aria-hidden="true" />
        <div><strong>资料与配置在处理阶段之外</strong><span>数据源和线索规则用于支撑发现阶段，不代替线索核实、材料判断或人工处置。</span></div>
      </section>
    </main>
  );
}
