import { Form, Input, InputNumber } from 'antd';
import { ModbusPointsList } from './ModbusPointsList';

export function ModbusTcpForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'host']} label="主机" rules={[{ required: true }]}>
        <Input placeholder="192.168.1.100" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'port']} label="端口" initialValue={502}>
        <InputNumber min={1} max={65535} />
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
