import { Col, Form, Input, InputNumber, Row, Select } from 'antd';
import { ModbusPointsList } from './ModbusPointsList';

const PARITIES = ['NONE', 'EVEN', 'ODD'];
const BAUD_RATES = [1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200];

export function ModbusRtuForm() {
  return (
    <>
      <Row gutter={16}>
        <Col span={16}>
          <Form.Item
            name={['protocolConfig', 'serialPort']}
            label="串口"
            rules={[{ required: true }]}
          >
            <Input placeholder="/dev/ttyUSB0" />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item name={['protocolConfig', 'unitId']} label="从站 ID" initialValue={1}>
            <InputNumber min={0} max={247} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={16}>
        <Col span={6}>
          <Form.Item name={['protocolConfig', 'baudRate']} label="波特率" initialValue={9600}>
            <Select options={BAUD_RATES.map((v) => ({ value: v, label: String(v) }))} showSearch />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name={['protocolConfig', 'dataBits']} label="数据位" initialValue={8}>
            <InputNumber min={5} max={8} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name={['protocolConfig', 'stopBits']} label="停止位" initialValue={1}>
            <InputNumber min={1} max={2} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name={['protocolConfig', 'parity']} label="校验" initialValue="NONE">
            <Select options={PARITIES.map((v) => ({ value: v, label: v }))} />
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
