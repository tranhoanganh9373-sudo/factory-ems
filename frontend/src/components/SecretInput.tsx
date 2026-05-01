import { Button, Input, Modal, Space, message } from 'antd';
import { useState } from 'react';
import { secretApi } from '@/api/secret';

interface Props {
  value?: string;
  onChange?: (ref: string) => void;
  refPrefix: string; // 例如 "mqtt/broker-prod"
  placeholder?: string;
}

export function SecretInput({ value, onChange, refPrefix, placeholder }: Props) {
  const [open, setOpen] = useState(false);
  const [plain, setPlain] = useState('');
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!plain) return;
    setSaving(true);
    try {
      const ref = `secret://${refPrefix}-${Date.now()}`;
      await secretApi.write(ref, plain);
      onChange?.(ref);
      message.success('已保存');
      setOpen(false);
      setPlain('');
    } catch {
      message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Space.Compact style={{ width: '100%' }}>
      <Input value={value} disabled placeholder={placeholder ?? '未设置'} />
      <Button onClick={() => setOpen(true)}>修改</Button>
      <Modal
        title="设置凭据"
        open={open}
        onOk={handleSave}
        confirmLoading={saving}
        onCancel={() => {
          setOpen(false);
          setPlain('');
        }}
        destroyOnClose
      >
        <Input.Password
          autoFocus
          value={plain}
          onChange={(e) => setPlain(e.target.value)}
          placeholder="输入明文，保存后将以 secret:// 引用形式存储"
          onPressEnter={handleSave}
        />
      </Modal>
    </Space.Compact>
  );
}
