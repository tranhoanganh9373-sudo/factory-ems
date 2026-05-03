/**
 * 把后端 `ChannelRuntimeState.lastErrorMessage` 的原始错误（多数是 Java 异常 / Modbus 协议码
 * / OPC UA UaException / MQTT ConnectException 透传字符串）翻译成运维能看懂的中文短语。
 *
 * 原始错误仍由调用方放在 Tooltip 里供 debug——这里只负责"主显示"那一行人话。
 */
export function humanizeChannelError(raw: string | null | undefined): string {
  if (!raw) return '-';
  const s = raw.toLowerCase();

  // ── Modbus 读寄存器 ─────────────────────────────────────────
  if (/readholding|readinput|readcoil/.test(s)) {
    if (/socket\s*timeout|read\s*timed\s*out|response\s*timeout/.test(s)) {
      return 'Modbus 读取超时（从机未响应，请检查 IP/端口/单元号）';
    }
    if (/connection\s*refused/.test(s)) return 'Modbus 连接被拒绝（请检查 IP/端口）';
    if (/illegal/.test(s)) return 'Modbus 寄存器地址非法（请核对 addr/数量）';
    if (/crc/.test(s)) return 'Modbus CRC 校验失败（线路干扰或波特率不匹配）';
    return 'Modbus 读寄存器失败';
  }

  // ── Modbus 重连 ─────────────────────────────────────────────
  if (/modbus.*reopen|reopen.*modbus/.test(s)) {
    if (/connection\s*refused/.test(s)) return 'Modbus 连接被拒绝（请检查 IP/端口）';
    if (/timeout|timed\s*out/.test(s)) return 'Modbus 连接超时（设备或网络不可达）';
    return 'Modbus 重连失败';
  }

  // ── OPC UA ─────────────────────────────────────────────────
  if (/opcua|opc[\s_-]?ua|uaexception/.test(s)) {
    if (/bad_certificate|bad_security|certificate/.test(s)) {
      return 'OPC UA 证书校验失败（请检查 SecurityMode/证书）';
    }
    if (/bad_user|bad_identity|user.*name|password/.test(s)) {
      return 'OPC UA 用户名或密码错误';
    }
    if (/connect.*refused|connection\s*refused/.test(s)) {
      return 'OPC UA 服务器拒绝连接（请检查 endpoint URL）';
    }
    if (/timeout|timed\s*out/.test(s)) return 'OPC UA 连接超时';
    if (/unknown\s*host|host\s*unreachable|no\s*route/.test(s)) {
      return 'OPC UA 主机不可达（请检查地址/网络）';
    }
    return 'OPC UA 通讯异常';
  }

  // ── MQTT ───────────────────────────────────────────────────
  if (/mqtt/.test(s)) {
    if (/refused.*authoriz|not\s*authorized|unauthorized/.test(s)) {
      return 'MQTT 服务器拒绝认证（请检查用户名/密码/ACL）';
    }
    if (/connection\s*refused|connect.*refused/.test(s)) {
      return 'MQTT 服务器拒绝连接（请检查 Broker 地址/端口）';
    }
    if (/timeout|timed\s*out/.test(s)) return 'MQTT 连接超时';
    if (/unknown\s*host|host\s*unreachable/.test(s)) return 'MQTT 主机不可达';
    if (/ssl|tls/.test(s)) return 'MQTT TLS 握手失败（请检查证书）';
    return 'MQTT 通讯异常';
  }

  // ── 通道配置/启动期异常 ────────────────────────────────────
  if (/cannot\s+invoke.*because.*null|nullpointerexception/.test(s)) {
    return '通道配置异常（必填字段缺失，请检查点位列表）';
  }
  if (/no\s+transport.*for|unknown\s+protocol/.test(s)) {
    return '协议未实现或配置错误';
  }

  // ── Sample 质量 / 周期失败兜底 ────────────────────────────
  if (/quality=bad/.test(s)) return '采集数据质量异常（设备返回 BAD）';
  if (/quality=uncertain/.test(s)) return '采集数据质量不确定（设备返回 UNCERTAIN）';
  if (/cycle had failures/.test(s)) return '本采集周期内多个点位失败';

  // ── 通用网络异常 ───────────────────────────────────────────
  if (/connection\s*refused/.test(s)) return '连接被拒绝（请检查地址/端口）';
  if (/connection\s*timed?\s*out|connect\s*timeout/.test(s)) {
    return '连接超时（请检查网络/防火墙）';
  }
  if (/no\s*route\s*to\s*host|host\s*unreachable/.test(s)) return '目标主机不可达';
  if (/network\s*unreachable/.test(s)) return '网络不可达';
  if (/unknown\s*host/.test(s)) return '主机名解析失败';
  if (/permission\s*denied/.test(s)) return '权限不足（端口或文件被占用）';
  if (/broken\s*pipe|reset\s*by\s*peer/.test(s)) return '连接被对端中断';

  // 未匹配——退化为截断的原文
  return raw.length > 30 ? `${raw.slice(0, 30)}…` : raw;
}
