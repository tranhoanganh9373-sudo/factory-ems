// 组织树节点类型软约束：仅前端层校验，后端不强制（保留现场异形结构的灵活度）。
//
// 规则：
//   1. 层级关系（自上而下）：PLANT < WORKSHOP < LINE < DEVICE < GROUP；
//   2. 子节点 type 的"等级数"必须 >= 父节点（同级允许，子层级不能反向上爬）；
//   3. OTHER 视为终结叶子，不允许再加子节点；其他类型可在任意位置嵌 OTHER 作为"杂项"。
//
// 注：纯前端 UX 提示，后端 API 仍接受任何合法的 nodeType 与父子组合，避免迁移卡死。

export const NODE_TYPES = ['PLANT', 'WORKSHOP', 'LINE', 'DEVICE', 'GROUP', 'OTHER'] as const;
export type NodeType = (typeof NODE_TYPES)[number];

const LEVEL: Record<NodeType, number> = {
  PLANT: 0,
  WORKSHOP: 1,
  LINE: 2,
  DEVICE: 3,
  GROUP: 4,
  OTHER: 5,
};

export function isTerminal(t: string | null | undefined): boolean {
  return t === 'OTHER';
}

/** 父节点 type（null=根），返回可作为子节点的 type 列表。 */
export function allowedChildTypes(parentType: string | null): NodeType[] {
  if (parentType == null) return [...NODE_TYPES];
  if (isTerminal(parentType)) return [];
  const min = LEVEL[parentType as NodeType];
  if (min == null) return [...NODE_TYPES];
  return NODE_TYPES.filter((t) => LEVEL[t] >= min);
}

/** 给指定 type 的节点找一个新父节点时，该父节点是否合规。 */
export function canAcceptChild(parentType: string | null, childType: string): boolean {
  if (parentType == null) return true;
  if (isTerminal(parentType)) return false;
  const childLevel = LEVEL[childType as NodeType];
  const parentLevel = LEVEL[parentType as NodeType];
  if (childLevel == null || parentLevel == null) return true;
  return childLevel >= parentLevel;
}
