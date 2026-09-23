import { ProTable, type ProColumns } from "@ant-design/pro-components";

interface DataTableProps<T extends Record<string, unknown>> {
  columns: ProColumns<T>[];
  data: T[];
  rowKey: keyof T & string;
  loading?: boolean;
}

export function DataTable<T extends Record<string, unknown>>({ columns, data, rowKey, loading }: DataTableProps<T>) {
  return (
    <ProTable<T>
      columns={columns}
      dataSource={data}
      rowKey={rowKey}
      loading={loading}
      search={false}
      options={false}
      pagination={false}
      cardProps={false}
    />
  );
}
