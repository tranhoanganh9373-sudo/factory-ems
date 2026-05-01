import { Button, Form, Input, InputNumber, Select, Space } from 'antd';
import type { FormListFieldData, FormListOperation } from 'antd/es/form/FormList';

interface Props {
  fields: FormListFieldData[];
  ops: FormListOperation;
}

const MODES = ['READ', 'SUBSCRIBE'];

export function OpcUaPointsList({ fields, ops }: Props) {
  return (
    <>
      <div style={{ marginBottom: 8, fontWeight: 500 }}>测点列表</div>
      {fields.map((f) => (
        <Space key={f.key} align="baseline" wrap style={{ marginBottom: 8 }}>
          <Form.Item name={[f.name, 'key']} label="Key" rules={[{ required: true }]}>
            <Input style={{ width: 120 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'nodeId']} label="NodeId" rules={[{ required: true }]}>
            <Input style={{ width: 220 }} placeholder="ns=2;s=Tag1" />
          </Form.Item>
          <Form.Item name={[f.name, 'mode']} label="模式" initialValue="READ">
            <Select
              style={{ width: 110 }}
              options={MODES.map((v) => ({ value: v, label: v }))}
            />
          </Form.Item>
          <Form.Item name={[f.name, 'samplingIntervalMs']} label="采样间隔(ms)" initialValue={1000}>
            <InputNumber min={0} style={{ width: 110 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'unit']} label="单位">
            <Input style={{ width: 70 }} />
          </Form.Item>
          <Button danger type="link" onClick={() => ops.remove(f.name)}>
            移除
          </Button>
        </Space>
      ))}
      <Button
        type="dashed"
        onClick={() => ops.add({ mode: 'READ', samplingIntervalMs: 1000 })}
        block
      >
        + 新增测点
      </Button>
    </>
  );
}
