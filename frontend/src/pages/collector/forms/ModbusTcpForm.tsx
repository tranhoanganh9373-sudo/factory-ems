import { Col, Form, Input, InputNumber, Row } from 'antd';
import { ModbusPointsList } from './ModbusPointsList';

export function ModbusTcpForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'host']} label="主机" rules={[{ required: true }]}>
        <Input placeholder="192.168.1.100" />
      </Form.Item>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item name={['protocolConfig', 'port']} label="端口" initialValue={502}>
            <InputNumber min={1} max={65535} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name={['protocolConfig', 'unitId']} label="从站 ID" initialValue={1}>
            <InputNumber min={0} max={247} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item name="description" label="描述">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <ModbusPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
