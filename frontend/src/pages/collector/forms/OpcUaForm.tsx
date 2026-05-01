import { Form, Input, Select } from 'antd';
import { SecretInput } from '@/components/SecretInput';
import { OPCUA_SECURITY_MODE_LABEL } from '@/utils/i18n-dict';
import { OpcUaPointsList } from './OpcUaPointsList';

export function OpcUaForm() {
  return (
    <>
      <Form.Item
        name={['protocolConfig', 'endpointUrl']}
        label="端点 URL"
        rules={[{ required: true }]}
      >
        <Input placeholder="opc.tcp://host:4840" />
      </Form.Item>
      <Form.Item
        name={['protocolConfig', 'securityMode']}
        label="安全模式"
        initialValue="NONE"
      >
        <Select
          options={Object.entries(OPCUA_SECURITY_MODE_LABEL).map(([v, l]) => ({
            value: v,
            label: l,
          }))}
        />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'usernameRef']} label="用户名（凭据引用）">
        <SecretInput refPrefix="opcua/username" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'passwordRef']} label="密码（凭据引用）">
        <SecretInput refPrefix="opcua/password" placeholder="未设置（可空）" />
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
        {(fields, ops) => <OpcUaPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
