import { Result, Space, Tag, Typography } from "antd";
interface MigrationPlaceholderProps {
  title: string;
  group: string;
}

export function MigrationPlaceholder({ title, group }: MigrationPlaceholderProps) {
  return (
    <section className="workspace-placeholder" aria-labelledby="workspace-title">
      <div className="workspace-heading">
        <div>
          <Typography.Text className="workspace-eyebrow">{group}</Typography.Text>
          <Typography.Title id="workspace-title" level={2}>{title}</Typography.Title>
        </div>
        <Space wrap>
          <Tag>开发环境</Tag>
          <Tag color="blue">迁移准备</Tag>
        </Space>
      </div>
      <Result
        status="info"
        title="React 应用壳已就绪"
        subTitle="该工作区尚未接入当前应用。"
      />
    </section>
  );
}
