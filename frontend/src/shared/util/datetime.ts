export function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return "时间未知";
  }
  return new Date(value).toLocaleString("zh-CN");
}
export function formatTimestamp(value: string) {
  if (!value) {
    return "-";
  }
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    return value;
  }
  return timestamp.toLocaleString();
}

export function formatRelativeTime(value: string) {
  if (!value) {
    return "时间未知";
  }
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    return value;
  }
  const diff = Date.now() - timestamp.getTime();
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) {
    return "刚刚";
  }
  if (diff < hour) {
    return `${Math.max(1, Math.floor(diff / minute))} 分钟前`;
  }
  if (diff < day) {
    return `${Math.max(1, Math.floor(diff / hour))} 小时前`;
  }
  return `${Math.max(1, Math.floor(diff / day))} 天前`;
}


