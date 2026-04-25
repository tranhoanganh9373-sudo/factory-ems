import { useState } from 'react';
import {
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tag,
  TimePicker,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import {
  shiftApi,
  isCrossMidnight,
  type ShiftDTO,
  type CreateShiftReq,
  type UpdateShiftReq,
} from '@/api/production';

interface ShiftFormValues {
  code: string;
  name: string;
  timeStart: Dayjs;
  timeEnd: Dayjs;
  sortOrder?: number;
  enabled?: boolean;
}

export default function ShiftsPage() {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<ShiftDTO | null>(null);
  const [form] = Form.useForm<ShiftFormValues>();

  const { data = [], isLoading } = useQuery({
    queryKey: ['shifts', 'all'],
    queryFn: () => shiftApi.list(false),
  });

  const createMut = useMutation({
    mutationFn: (req: CreateShiftReq) => shiftApi.create(req),
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['shifts'] });
      setOpen(false);
      form.resetFields();
    },
  });
  const updateMut = useMutation({
    mutationFn: ({ id, req }: { id: number; req: UpdateShiftReq }) => shiftApi.update(id, req),
    onSuccess: () => {
      message.success('已更新');
      qc.invalidateQueries({ queryKey: ['shifts'] });
      setOpen(false);
      setEditing(null);
      form.resetFields();
    },
  });
  const deleteMut = useMutation({
    mutationFn: (id: number) => shiftApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['shifts'] });
    },
  });

  function openCreate() {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      code: '',
      name: '',
      timeStart: dayjs().hour(8).minute(0).second(0),
      timeEnd: dayjs().hour(20).minute(0).second(0),
      sortOrder: 0,
    });
    setOpen(true);
  }

  function openEdit(s: ShiftDTO) {
    setEditing(s);
    form.setFieldsValue({
      code: s.code,
      name: s.name,
      timeStart: dayjs(s.timeStart, 'HH:mm:ss'),
      timeEnd: dayjs(s.timeEnd, 'HH:mm:ss'),
      sortOrder: s.sortOrder,
      enabled: s.enabled,
    });
    setOpen(true);
  }

  async function handleOk() {
    let v: ShiftFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const ts = v.timeStart.format('HH:mm:ss');
    const te = v.timeEnd.format('HH:mm:ss');
    if (editing) {
      updateMut.mutate({
        id: editing.id,
        req: {
          name: v.name,
          timeStart: ts,
          timeEnd: te,
          enabled: v.enabled,
          sortOrder: v.sortOrder,
        },
      });
    } else {
      createMut.mutate({
        code: v.code,
        name: v.name,
        timeStart: ts,
        timeEnd: te,
        sortOrder: v.sortOrder,
      });
    }
  }

  return (
    <Card
      title="班次管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建班次
        </Button>
      }
    >
      <Table<ShiftDTO>
        rowKey="id"
        loading={isLoading}
        dataSource={data}
        pagination={false}
        columns={[
          { title: '代码', dataIndex: 'code', width: 100 },
          { title: '名称', dataIndex: 'name' },
          {
            title: '起止时间',
            render: (_, s) => (
              <Space>
                <span>
                  {s.timeStart} → {s.timeEnd}
                </span>
                {isCrossMidnight(s.timeStart, s.timeEnd) && (
                  <Tag color="orange">跨零点</Tag>
                )}
              </Space>
            ),
          },
          { title: '排序', dataIndex: 'sortOrder', width: 80 },
          {
            title: '启用',
            dataIndex: 'enabled',
            width: 70,
            render: (e: boolean) => (e ? <Tag color="green">是</Tag> : <Tag>否</Tag>),
          },
          {
            title: '操作',
            width: 160,
            render: (_, s) => (
              <Space>
                <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(s)}>
                  编辑
                </Button>
                <Popconfirm
                  title={`删除 ${s.code}？`}
                  onConfirm={() => deleteMut.mutate(s.id)}
                >
                  <Button size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editing ? `编辑班次：${editing.code}` : '新建班次'}
        open={open}
        onOk={handleOk}
        onCancel={() => {
          setOpen(false);
          setEditing(null);
        }}
        confirmLoading={createMut.isPending || updateMut.isPending}
        destroyOnClose
      >
        <Form<ShiftFormValues> form={form} layout="vertical">
          <Form.Item
            name="code"
            label="代码"
            rules={[{ required: true, max: 32 }]}
          >
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, max: 64 }]}>
            <Input />
          </Form.Item>
          <Space>
            <Form.Item name="timeStart" label="开始" rules={[{ required: true }]}>
              <TimePicker format="HH:mm" minuteStep={15} />
            </Form.Item>
            <Form.Item name="timeEnd" label="结束" rules={[{ required: true }]}>
              <TimePicker format="HH:mm" minuteStep={15} />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} />
            </Form.Item>
          </Space>
          {editing && (
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          )}
          <div style={{ color: '#888', fontSize: 12 }}>
            提示：若 结束 ≤ 开始，将作为跨零点班次（如 22:00 → 06:00）。
          </div>
        </Form>
      </Modal>
    </Card>
  );
}
