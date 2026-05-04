import { Button, Form, Input, Popover, Select, Space, Typography } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { VIRTUAL_MODE_LABEL } from '@/utils/i18n-dict';

const PARAMS_HELP = (
  <div style={{ maxWidth: 380, fontSize: 12, lineHeight: 1.6 }}>
    <Typography.Paragraph style={{ marginBottom: 8 }}>
      参数必须是合法 JSON 对象。每种模式支持的字段如下（缺省项使用默认值）：
    </Typography.Paragraph>
    <Typography.Paragraph style={{ marginBottom: 6 }}>
      <strong>恒定值 CONSTANT</strong>
      <br />
      <code>{'{"value": 42}'}</code>
      <br />
      仅一个字段：<code>value</code>（默认 0）
    </Typography.Paragraph>
    <Typography.Paragraph style={{ marginBottom: 6 }}>
      <strong>正弦波 SINE</strong>
      <br />
      <code>{'{"amplitude": 10, "periodSec": 60, "offset": 0}'}</code>
      <br />
      <code>amplitude</code> 振幅（默认 1）；<code>periodSec</code> 周期秒（默认 60）；
      <code>offset</code> 直流偏置（默认 0）
    </Typography.Paragraph>
    <Typography.Paragraph style={{ marginBottom: 6 }}>
      <strong>随机游走 RANDOM_WALK</strong>
      <br />
      <code>{'{"min": 0, "max": 100, "maxStep": 1, "start": 50}'}</code>
      <br />
      <code>min</code>/<code>max</code> 边界（默认 0/100）；<code>maxStep</code> 单步最大变化（默认
      1）；
      <code>start</code> 初始值（默认 (min+max)/2）
    </Typography.Paragraph>
    <Typography.Paragraph style={{ marginBottom: 0 }}>
      <strong>日历曲线 CALENDAR_CURVE</strong>
      <br />
      <code>{'{"weekdayPeak": 100, "weekendPeak": 30, "peakHour": 9, "sigma": 3}'}</code>
      <br />
      工作日 / 周末峰值，<code>peakHour</code> 峰值小时（0–23），<code>sigma</code>{' '}
      钟形标准差（小时）
    </Typography.Paragraph>
  </div>
);

export function VirtualForm() {
  return (
    <>
      <Form.Item name="description" label="描述">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, { add, remove }) => (
          <>
            <div
              style={{
                marginBottom: 8,
                fontWeight: 500,
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
            >
              测点列表
              <Popover content={PARAMS_HELP} title="参数 JSON 格式说明" placement="rightTop">
                <QuestionCircleOutlined
                  style={{ color: '#1677ff', cursor: 'help' }}
                  aria-label="参数 JSON 格式帮助"
                />
              </Popover>
            </div>
            {fields.map((f) => (
              <Space key={f.key} align="baseline" wrap style={{ marginBottom: 8 }}>
                <Form.Item name={[f.name, 'key']} label="标签名" rules={[{ required: true }]}>
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
