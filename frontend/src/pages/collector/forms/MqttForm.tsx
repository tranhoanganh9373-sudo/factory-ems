import { Form, Input, Select, Switch } from 'antd';
import { DurationInput } from '@/components/DurationInput';
import { SecretInput } from '@/components/SecretInput';
import { MqttPointsList } from './MqttPointsList';

const QOS_LEVELS = [
  { value: 0, label: 'QoS 0 (at most once)' },
  { value: 1, label: 'QoS 1 (at least once)' },
];

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
      <Form.Item
        name={['protocolConfig', 'clientId']}
        label="Client ID"
        rules={[{ required: true }]}
      >
        <Input />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'usernameRef']} label="用户名（凭据引用）">
        <SecretInput refPrefix="mqtt/username" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'passwordRef']} label="密码（凭据引用）">
        <SecretInput refPrefix="mqtt/password" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'tlsCaCertRef']} label="CA 证书（凭据引用）">
        <SecretInput refPrefix="mqtt/tls-ca" placeholder="未设置（明文 broker 不填）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'qos']} label="QoS" initialValue={1}>
        <Select options={QOS_LEVELS} />
      </Form.Item>
      <Form.Item
        name={['protocolConfig', 'cleanSession']}
        label="Clean Session"
        valuePropName="checked"
        initialValue={true}
      >
        <Switch />
      </Form.Item>
      <Form.Item
        name={['protocolConfig', 'keepAlive']}
        label="KeepAlive"
        rules={[{ required: true }]}
        initialValue="PT60S"
      >
        <DurationInput />
      </Form.Item>
      <Form.Item name="description" label="描述">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <MqttPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
