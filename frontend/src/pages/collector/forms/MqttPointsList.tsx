import { Button, Form, Input, Space } from 'antd';
import type { FormListFieldData, FormListOperation } from 'antd/es/form/FormList';

interface Props {
  fields: FormListFieldData[];
  ops: FormListOperation;
}

export function MqttPointsList({ fields, ops }: Props) {
  return (
    <>
      <div style={{ marginBottom: 8, fontWeight: 500 }}>测点列表</div>
      {fields.map((f) => (
        <Space key={f.key} align="baseline" wrap style={{ marginBottom: 8 }}>
          <Form.Item name={[f.name, 'key']} label="标签名" rules={[{ required: true }]}>
            <Input style={{ width: 120 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'topic']} label="Topic" rules={[{ required: true }]}>
            <Input style={{ width: 220 }} placeholder="factory/line1/temp" />
          </Form.Item>
          <Form.Item name={[f.name, 'jsonPath']} label="JSONPath" rules={[{ required: true }]}>
            <Input style={{ width: 160 }} placeholder="$.value" />
          </Form.Item>
          <Form.Item name={[f.name, 'unit']} label="单位">
            <Input style={{ width: 70 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'timestampJsonPath']} label="时间戳路径（可选）">
            <Input style={{ width: 160 }} placeholder="$.ts" />
          </Form.Item>
          <Button danger type="link" onClick={() => ops.remove(f.name)}>
            移除
          </Button>
        </Space>
      ))}
      <Button type="dashed" onClick={() => ops.add({})} block>
        + 新增测点
      </Button>
    </>
  );
}
