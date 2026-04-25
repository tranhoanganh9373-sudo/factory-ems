import { TreeSelect } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { orgTreeApi, OrgNodeDTO } from '@/api/orgtree';

export function OrgTreeSelector({
  value,
  onChange,
  allowClear = true,
  placeholder = '选择组织节点',
  style,
}: {
  value?: number | null;
  onChange?: (v: number | null) => void;
  allowClear?: boolean;
  placeholder?: string;
  style?: React.CSSProperties;
}) {
  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
    staleTime: 60_000,
  });
  interface SelectNode {
    title: string;
    value: number;
    children: SelectNode[];
  }
  const toSelectData = (nodes: OrgNodeDTO[]): SelectNode[] =>
    nodes.map((n) => ({ title: n.name, value: n.id, children: toSelectData(n.children) }));
  return (
    <TreeSelect
      treeData={toSelectData(tree)}
      treeDefaultExpandAll
      allowClear={allowClear}
      placeholder={placeholder}
      value={value ?? undefined}
      onChange={(v) => onChange?.(v ?? null)}
      style={style}
    />
  );
}
