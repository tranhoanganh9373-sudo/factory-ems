import { Button, Form, Input, InputNumber, Select, Space } from 'antd';
import type { FormListFieldData, FormListOperation } from 'antd/es/form/FormList';

interface Props {
  fields: FormListFieldData[];
  ops: FormListOperation;
}

// 与后端 com.ems.collector.config.DataType 严格一致
const DATA_TYPES = [
  { value: 'UINT16', label: 'UInt16' },
  { value: 'INT16', label: 'Int16' },
  { value: 'UINT32', label: 'UInt32' },
  { value: 'INT32', label: 'Int32' },
  { value: 'FLOAT32', label: 'Float32' },
  { value: 'FLOAT64', label: 'Float64' },
  { value: 'BIT', label: 'Bit' },
];

// 与 ModbusTcpAdapterTransport / ModbusRtuAdapterTransport 解析的字符串一致
const REGISTER_KINDS = [
  { value: 'HOLDING', label: '保持寄存器' },
  { value: 'INPUT', label: '输入寄存器' },
  { value: 'COIL', label: '线圈' },
  { value: 'DISCRETE_INPUT', label: '离散输入' },
];

// 与后端 com.ems.collector.config.ByteOrder 严格一致
const BYTE_ORDERS = [
  { value: 'ABCD', label: 'ABCD (big-endian)' },
  { value: 'CDAB', label: 'CDAB (word swap)' },
  { value: 'BADC', label: 'BADC (byte swap)' },
  { value: 'DCBA', label: 'DCBA (little-endian)' },
];

export function ModbusPointsList({ fields, ops }: Props) {
  return (
    <>
      <div style={{ marginBottom: 8, fontWeight: 500 }}>测点列表</div>
      {fields.map((f) => (
        <Space key={f.key} align="baseline" wrap style={{ marginBottom: 8 }}>
          <Form.Item name={[f.name, 'key']} label="标签名" rules={[{ required: true }]}>
            <Input style={{ width: 100 }} />
          </Form.Item>
          <Form.Item
            name={[f.name, 'registerKind']}
            label="寄存器"
            rules={[{ required: true }]}
            initialValue="HOLDING"
          >
            <Select style={{ width: 110 }} options={REGISTER_KINDS} />
          </Form.Item>
          <Form.Item name={[f.name, 'address']} label="地址" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item
            name={[f.name, 'quantity']}
            label="数量"
            rules={[{ required: true }]}
            initialValue={1}
          >
            <InputNumber min={1} max={125} style={{ width: 70 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'dataType']} label="数据类型" initialValue="FLOAT32">
            <Select style={{ width: 90 }} options={DATA_TYPES} />
          </Form.Item>
          <Form.Item name={[f.name, 'byteOrder']} label="字节序">
            <Select allowClear style={{ width: 130 }} options={BYTE_ORDERS} />
          </Form.Item>
          <Form.Item name={[f.name, 'scale']} label="倍率" initialValue={1}>
            <InputNumber style={{ width: 70 }} />
          </Form.Item>
          <Form.Item name={[f.name, 'unit']} label="单位">
            <Input style={{ width: 60 }} />
          </Form.Item>
          <Button danger type="link" onClick={() => ops.remove(f.name)}>
            移除
          </Button>
        </Space>
      ))}
      <Button
        type="dashed"
        onClick={() =>
          ops.add({ registerKind: 'HOLDING', dataType: 'FLOAT32', quantity: 1, scale: 1 })
        }
        block
      >
        + 新增测点
      </Button>
    </>
  );
}
