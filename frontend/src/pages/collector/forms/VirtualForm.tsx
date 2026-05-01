import { Button, Form, Input, Select, Space } from 'antd';
import { VIRTUAL_MODE_LABEL } from '@/utils/i18n-dict';

export function VirtualForm() {
  return (
    <>
      <Form.Item
        name={['protocolConfig', 'pollInterval']}
        label="轮询间隔（ISO-8601，例 PT1S）"
        rules={[{ required: true }]}
        initialValue="PT1S"
      >
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, { add, remove }) => (
          <>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>测点列表</div>
            {fields.map((f) => (
              <Space key={f.key} align="baseline" wrap style={{ marginBottom: 8 }}>
                <Form.Item name={[f.name, 'key']} label="Key" rules={[{ required: true }]}>
                  <Input style={{ width: 120 }} />
                </Form.Item>
                <Form.Item
                  name={[f.name, 'mode']}
                  label="模式"
                  rules={[{ required: true }]}
                  initialValue="CONSTANT"
                >
                  <Select
                    style={{ width: 140 }}
                    options={Object.entries(VIRTUAL_MODE_LABEL).map(([v, l]) => ({
                      value: v,
                      label: l,
                    }))}
                  />
                </Form.Item>
                <Form.Item name={[f.name, 'unit']} label="单位">
                  <Input style={{ width: 80 }} />
                </Form.Item>
                <Form.Item
                  name={[f.name, 'params']}
                  label="参数 (JSON)"
                  rules={[{ required: true, message: '需要 JSON 对象' }]}
                >
                  <Input.TextArea rows={2} placeholder='{"value": 42}' style={{ width: 240 }} />
                </Form.Item>
                <Button danger type="link" onClick={() => remove(f.name)}>
                  移除
                </Button>
              </Space>
            ))}
            <Button
              type="dashed"
              onClick={() => add({ key: '', mode: 'CONSTANT', params: '{"value": 0}' })}
              block
            >
              + 新增测点
            </Button>
          </>
        )}
      </Form.List>
    </>
  );
}
