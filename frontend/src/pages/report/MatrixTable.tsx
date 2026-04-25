import { Table } from 'antd';
import type { ReportMatrix } from '@/api/reportPreset';

interface Props {
  matrix: ReportMatrix;
}

interface Row {
  key: string;
  label: string;
  [col: string]: string | number | null | undefined;
}

/** Renders a ReportMatrix as a 2-axis HTML table (rows x cols). */
export default function MatrixTable({ matrix }: Props) {
  const data: Row[] = matrix.rowLabels.map((row, ri) => {
    const r: Row = { key: String(ri), label: row };
    matrix.colLabels.forEach((c, ci) => {
      const v = matrix.values[ri]?.[ci];
      r[c] = v == null ? null : Number(v);
    });
    return r;
  });

  const columns = [
    {
      title: matrix.rowAxis,
      dataIndex: 'label',
      fixed: 'left' as const,
      width: 160,
    },
    ...matrix.colLabels.map((c) => ({
      title: c,
      dataIndex: c,
      width: 100,
      render: (v: number | null) => (v == null ? '—' : Number(v).toFixed(2)),
    })),
  ];

  return (
    <Table<Row>
      size="small"
      bordered
      pagination={false}
      scroll={{ x: 'max-content' }}
      dataSource={data}
      columns={columns}
      summary={() => {
        const totals = matrix.colLabels.map((_, ci) => {
          let sum = 0;
          let any = false;
          matrix.values.forEach((row) => {
            const v = row[ci];
            if (v != null) {
              sum += Number(v);
              any = true;
            }
          });
          return any ? sum : null;
        });
        return (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0}>合计</Table.Summary.Cell>
            {totals.map((v, i) => (
              <Table.Summary.Cell key={i} index={i + 1}>
                {v == null ? '—' : v.toFixed(2)}
              </Table.Summary.Cell>
            ))}
          </Table.Summary.Row>
        );
      }}
    />
  );
}
