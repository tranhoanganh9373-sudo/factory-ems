import { Form, Input, InputNumber, Select, Switch } from 'antd';
import { SecretInput } from '@/components/SecretInput';
import { MqttPointsList } from './MqttPointsList';

const QOS_LEVELS = [0, 1, 2];

export function MqttForm() {
  return (
    <>
      <Form.Item
        name={['protocolConfig', 'brokerUrl']}
        label="Broker URL"
        rules={[{ required: true }]}
      >
        <Input placeholder="tcp://broker.example.com:1883" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'clientId']} label="Client ID" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'usernameRef']} label="用户名（凭据引用）">
        <SecretInput refPrefix="mqtt/username" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'passwordRef']} label="密码（凭据引用）">
        <SecretInput refPrefix="mqtt/password" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'qos']} label="QoS" initialValue={1}>
        <Select options={QOS_LEVELS.map((v) => ({ value: v, label: `Qos ${v}` }))} />
      </Form.Item>
      <Form.Item
        name={['protocolConfig', 'cleanSession']}
        label="Clean Session"
        valuePropName="checked"
        initialValue={true}
      >
        <Switch />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'keepAlive']} label="保活时长（秒）" initialValue={60}>
        <InputNumber min={1} />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <MqttPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
