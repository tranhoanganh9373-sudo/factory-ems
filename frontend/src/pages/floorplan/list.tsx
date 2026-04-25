import { useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Space,
  Spin,
  TreeSelect,
  Upload,
} from 'antd';
import { PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  floorplanApi,
  uploadFloorplan,
  floorplanImageUrl,
  type FloorplanDTO,
} from '@/api/floorplan';
import { orgTreeApi, type OrgNodeDTO } from '@/api/orgtree';

function buildTreeData(nodes: OrgNodeDTO[]): object[] {
  return nodes.map((n) => ({
    title: `${n.name} (${n.code})`,
    value: n.id,
    key: n.id,
    children: n.children?.length ? buildTreeData(n.children) : undefined,
  }));
}

interface UploadFormValues {
  name: string;
  orgNodeId: number;
  file?: File;
}

export default function FloorplanListPage() {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [pickedFile, setPickedFile] = useState<File | null>(null);
  const [form] = Form.useForm<UploadFormValues>();

  const { data: list = [], isLoading } = useQuery({
    queryKey: ['floorplans'],
    queryFn: () => floorplanApi.list(),
  });
  const { data: tree = [] } = useQuery({
    queryKey: ['orgtree'],
    queryFn: () => orgTreeApi.getTree(),
  });

  const uploadMut = useMutation({
    mutationFn: ({ file, name, orgNodeId }: { file: File; name: string; orgNodeId: number }) =>
      uploadFloorplan(file, name, orgNodeId),
    onSuccess: () => {
      message.success('已上传');
      qc.invalidateQueries({ queryKey: ['floorplans'] });
      setOpen(false);
      setPickedFile(null);
      form.resetFields();
    },
    onError: (e: unknown) => {
      message.error(e instanceof Error ? e.message : '上传失败');
    },
  });
  const deleteMut = useMutation({
    mutationFn: (id: number) => floorplanApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['floorplans'] });
    },
  });

  async function handleUpload() {
    if (!pickedFile) {
      message.error('请选择图片文件');
      return;
    }
    let v: UploadFormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    uploadMut.mutate({ file: pickedFile, name: v.name, orgNodeId: v.orgNodeId });
  }

  return (
    <Card
      title="平面图列表"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          上传新图
        </Button>
      }
    >
      {isLoading ? (
        <Spin />
      ) : list.length === 0 ? (
        <Empty description="暂无平面图" />
      ) : (
        <Row gutter={[16, 16]}>
          {list.map((f: FloorplanDTO) => (
            <Col key={f.id} xs={24} sm={12} md={8} lg={6}>
              <Card
                hoverable
                cover={
                  <div
                    style={{
                      height: 160,
                      background: `#f0f0f0 url(${floorplanImageUrl(f.id)}) center/contain no-repeat`,
                    }}
                  />
                }
                actions={[
                  <Link key="edit" to={`/floorplan/editor/${f.id}`}>
                    <EditOutlined /> 编辑测点
                  </Link>,
                  <Popconfirm
                    key="del"
                    title={`删除 ${f.name}？`}
                    onConfirm={() => deleteMut.mutate(f.id)}
                  >
                    <a style={{ color: '#cf1322' }}>
                      <DeleteOutlined /> 删除
                    </a>
                  </Popconfirm>,
                ]}
              >
                <Card.Meta
                  title={f.name}
                  description={`${f.widthPx}×${f.heightPx} · ${(f.fileSizeBytes / 1024).toFixed(1)} KB`}
                />
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Modal
        title="上传平面图"
        open={open}
        onOk={handleUpload}
        confirmLoading={uploadMut.isPending}
        onCancel={() => {
          setOpen(false);
          setPickedFile(null);
        }}
        destroyOnClose
      >
        <Form<UploadFormValues> form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, max: 64 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="orgNodeId" label="组织节点" rules={[{ required: true }]}>
            <TreeSelect
              treeData={buildTreeData(tree)}
              treeDefaultExpandAll
              showSearch
              placeholder="选择车间"
            />
          </Form.Item>
          <Form.Item label="图片文件" required>
            <Upload
              accept="image/*"
              beforeUpload={(f) => {
                setPickedFile(f);
                return false;
              }}
              maxCount={1}
              fileList={
                pickedFile
                  ? [{ uid: '-1', name: pickedFile.name, status: 'done', size: pickedFile.size }]
                  : []
              }
              onRemove={() => setPickedFile(null)}
            >
              <Button icon={<PlusOutlined />}>选择文件</Button>
            </Upload>
            <Space style={{ marginTop: 4, color: '#888', fontSize: 12 }}>
              支持 PNG/JPG/SVG 等图片格式
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
