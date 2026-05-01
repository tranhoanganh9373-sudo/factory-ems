import { Form, Input, InputNumber, Select } from 'antd';
import { ModbusPointsList } from './ModbusPointsList';

const PARITIES = ['NONE', 'EVEN', 'ODD'];

export function ModbusRtuForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'serialPort']} label="串口" rules={[{ required: true }]}>
        <Input placeholder="/dev/ttyUSB0" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'baudRate']} label="波特率" initialValue={9600}>
        <InputNumber min={1200} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'dataBits']} label="数据位" initialValue={8}>
        <InputNumber min={5} max={8} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'stopBits']} label="停止位" initialValue={1}>
        <InputNumber min={1} max={2} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'parity']} label="校验" initialValue="NONE">
        <Select options={PARITIES.map((v) => ({ value: v, label: v }))} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'unitId']} label="从站 ID" initialValue={1}>
        <InputNumber min={0} max={247} />
      </Form.Item>
      <Form.Item
        name={['protocolConfig', 'pollInterval']}
        label="轮询间隔（ISO-8601，例 PT5S）"
        rules={[{ required: true }]}
        initialValue="PT5S"
      >
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <ModbusPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
