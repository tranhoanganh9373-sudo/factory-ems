import { Button, Form, Input, InputNumber, Select, Space } from 'antd';
import type { FormListFieldData, FormListOperation } from 'antd/es/form/FormList';

interface Props {
  fields: FormListFieldData[];
  ops: FormListOperation;
}

const DATA_TYPES = ['U16', 'I16', 'U32', 'I32', 'F32'];

export function ModbusPointsList({ fields, ops }: Props) {
  return (
    <>
      <div style={{ marginBottom: 8, fontWeight: 500 }}>测点列表</div>
      {fields.map((f) => (
        <Space key={f.key} align="baseline" wrap style={{ marginBottom: 8 }}>
          <Form.Item name={[f.name, 'key']} label="Key" rules={[{ required: true }]}>
            <Input style={{ width: 120 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'address']} label="地址" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'dataType']} label="数据类型" initialValue="F32">
            <Select
              style={{ width: 100 }}
              options={DATA_TYPES.map((v) => ({ value: v, label: v }))}
            />
          </Form.Item>
          <Form.Item name={[f.name, 'scale']} label="倍率" initialValue={1}>
            <InputNumber style={{ width: 80 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'offset']} label="偏移" initialValue={0}>
            <InputNumber style={{ width: 80 }} />
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
        onClick={() => ops.add({ dataType: 'F32', scale: 1, offset: 0 })}
        block
      >
        + 新增测点
      </Button>
    </>
  );
}
