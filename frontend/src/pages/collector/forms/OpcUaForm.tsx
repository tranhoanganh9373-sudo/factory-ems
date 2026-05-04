import { Alert, Form, Input, Select } from 'antd';
import { SecretInput } from '@/components/SecretInput';
import { OPCUA_SECURITY_MODE_LABEL } from '@/utils/i18n-dict';
import { OpcUaPointsList } from './OpcUaPointsList';

export function OpcUaForm() {
  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="v1 仅支持 SecurityMode.NONE"
        description="SIGN / SIGN_AND_ENCRYPT 需要证书审批流程，将在 v2 提供。当前版本配置非 NONE 模式会在启动通道时报错。"
      />
      <Form.Item
        name={['protocolConfig', 'endpointUrl']}
        label="端点 URL"
        rules={[{ required: true }]}
      >
        <Input placeholder="opc.tcp://host:4840" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'securityMode']} label="安全模式" initialValue="NONE">
        <Select
          options={Object.entries(OPCUA_SECURITY_MODE_LABEL).map(([v, l]) => ({
            value: v,
            label: l,
            disabled: v !== 'NONE',
          }))}
        />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'usernameRef']} label="用户名（凭据引用）">
        <SecretInput refPrefix="opcua/username" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'passwordRef']} label="密码（凭据引用）">
        <SecretInput refPrefix="opcua/password" placeholder="未设置（可空）" />
      </Form.Item>
      <Form.Item name="description" label="描述">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <OpcUaPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
