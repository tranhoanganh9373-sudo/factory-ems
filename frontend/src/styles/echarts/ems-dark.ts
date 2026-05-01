export const emsDarkTheme = {
  color: ['#00C2CC', '#FFD732', '#4FA8FF', '#3DCB6E', '#FFA940', '#C77DFF', '#A8B2BD', '#FF6464'],
  backgroundColor: 'transparent',
  textStyle: { color: '#A8B2BD', fontFamily: 'PingFang SC, sans-serif' },
  title: { textStyle: { color: '#E8ECF1', fontWeight: 600 } },
  legend: { textStyle: { color: '#A8B2BD' } },
  axisPointer: { lineStyle: { color: '#3A4658' } },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#252F3E' } },
    axisTick: { lineStyle: { color: '#252F3E' } },
    axisLabel: { color: '#A8B2BD' },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#6B7682' },
    splitLine: { lineStyle: { color: '#252F3E', type: 'dashed' } },
  },
};
