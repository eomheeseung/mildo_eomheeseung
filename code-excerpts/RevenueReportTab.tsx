// 관리자 매출일보(광고비 대시보드) 탭 (발췌)
//
// [포트폴리오 발췌] 민감정보 없음. AD = 디자인 토큰(색/폰트) 객체.
//
// 컴포넌트 설계 포인트
//  · 백엔드 광고 연동이 준비되기 전에도 화면이 "깨지지 않고" 동작하게: 로딩/빈상태/미연동을
//    모두 명시적으로 렌더(graceful degradation). 처음엔 데이터 전부 '-' 로 레이아웃만 먼저 붙였다.
//  · 금액 표시 규칙을 한 곳(포맷터)에 모음. 광고비는 소수점이 올 수 있어(구글 costMicros 환산)
//    "합산은 서버가 정밀하게, 화면은 Math.round로 표시만 정수" 로 분리 → 표에 54,557원 처럼 깔끔.
//  · ROAS는 광고비 0이면 서버가 null → 화면은 '-'. 순이익 음수는 색으로 구분.
import React, { useEffect, useState } from 'react';
import { View, Text, TouchableOpacity, ScrollView, ActivityIndicator, Alert } from 'react-native';
import { Feather } from '@expo/vector-icons';
import { apiClient } from '../api/client';
import { AD } from './adminTokens'; // 디자인 토큰(색/폰트/간격)

export function RevenueReportTab() {
  const [summary, setSummary] = useState<any>(null);
  const [series, setSeries] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [period, setPeriod] = useState<7 | 30>(30);
  const [syncing, setSyncing] = useState(false);

  const rangeOf = (days: number) => {
    const to = new Date();
    const from = new Date();
    from.setDate(from.getDate() - (days - 1));
    const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return { from: fmt(from), to: fmt(to) };
  };

  const load = async (days = period) => {
    setIsLoading(true);
    const { from, to } = rangeOf(days);
    try {
      const [s, sr] = await Promise.all([
        apiClient.getAdSpendSummary({ from, to }),
        apiClient.getAdSpendSeries({ from, to }),
      ]);
      setSummary(s.success ? s.data : null);
      setSeries(sr.success && Array.isArray(sr.data) ? sr.data : []);
    } catch {
      setSummary(null);
      setSeries([]);
    }
    setIsLoading(false);
  };

  useEffect(() => { load(period); }, [period]);

  // 광고 API를 즉시 당겨오는 수동 동기화(배치를 기다리지 않고 과거분 적재)
  const handleSync = async () => {
    if (syncing) return;
    setSyncing(true);
    try {
      const { from, to } = rangeOf(period);
      const res = await apiClient.syncAdSpend({ from, to });
      if (res.success) { await load(period); Alert.alert('동기화 완료', `${res.data?.upsertedRows ?? 0}개 행 갱신됨`); }
      else Alert.alert('동기화 실패', res.message || '광고 API 조회에 실패했습니다.');
    } catch {
      Alert.alert('동기화 실패', '오류가 발생했습니다.');
    }
    setSyncing(false);
  };

  // ── 표시 포맷: 반올림(A안) + 콤마 + '원'. 카운트/ROAS는 별도 규칙 ──
  const won = (n?: number | null) => (n == null ? '-' : `${Math.round(Number(n)).toLocaleString('ko-KR')}원`);
  const roasStr = (r?: number | null) => (r == null ? '-' : `${Number(r).toFixed(2)}x`); // 광고비 0 → null → '-'
  const num = (n?: number | null) => (n == null ? '-' : Number(n).toLocaleString('ko-KR'));
  const signed = (n?: number | null) => (n == null ? '-' : `${n < 0 ? '−' : ''}${Math.round(Math.abs(Number(n))).toLocaleString('ko-KR')}원`);

  const platformSpend = (p: string) => (summary?.platforms ?? []).find((x: any) => x.platform === p)?.spend ?? 0;

  const kpis = [
    { l: '베리 매출', v: won(summary?.berryRevenue), neg: false },
    { l: '총 광고비', v: won(summary?.totalSpend), neg: false },
    { l: 'ROAS', v: roasStr(summary?.roas), neg: false },
    { l: '순이익', v: signed(summary?.netProfit), neg: (summary?.netProfit ?? 0) < 0 },
    { l: '전환 / 클릭', v: `${num(summary?.conversions)} / ${num(summary?.clicks)}`, neg: false },
  ];

  return (
    <ScrollView style={{ flex: 1, backgroundColor: AD.ground }} contentContainerStyle={{ padding: 24 }}>
      {/* 헤더 + 기간 토글 + 수동 동기화 */}
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <Text style={{ fontSize: 22, fontWeight: '800', color: AD.ink }}>매출일보</Text>
        <View style={{ flex: 1 }} />
        {[7, 30].map((d) => (
          <TouchableOpacity key={d} onPress={() => setPeriod(d as 7 | 30)}
            style={{ paddingHorizontal: 12, paddingVertical: 7, borderRadius: 8, borderWidth: 1, borderColor: period === d ? AD.accent : AD.line, backgroundColor: period === d ? AD.accentSoft : AD.surface }}>
            <Text style={{ color: period === d ? AD.accent : AD.ink2 }}>최근 {d}일</Text>
          </TouchableOpacity>
        ))}
        <TouchableOpacity onPress={handleSync} disabled={syncing}
          style={{ flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 14, paddingVertical: 7, borderRadius: 8, backgroundColor: AD.accent, opacity: syncing ? 0.6 : 1 }}>
          {syncing ? <ActivityIndicator color="#fff" size="small" /> : <Feather name="refresh-cw" size={13} color="#fff" />}
          <Text style={{ color: '#fff', fontWeight: '700' }}>광고비 동기화</Text>
        </TouchableOpacity>
      </View>

      {/* 요약 KPI — 미연동/로딩이면 '…' 또는 '-' 로 안전하게 */}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12, marginTop: 20 }}>
        {kpis.map((k) => (
          <View key={k.l} style={{ flex: 1, minWidth: 150, backgroundColor: AD.surface, borderWidth: 1, borderColor: AD.line, borderRadius: 12, padding: 15 }}>
            <Text style={{ fontSize: 12, color: AD.ink2 }}>{k.l}</Text>
            <Text style={{ fontSize: 22, fontWeight: '800', color: k.neg ? AD.neg : AD.ink, marginTop: 6 }}>
              {isLoading ? '…' : k.v}
            </Text>
          </View>
        ))}
      </View>

      {/* 일별 시계열 — 로딩/빈상태를 명시적으로 렌더 (표 렌더 코드는 생략) */}
      {isLoading ? (
        <ActivityIndicator color={AD.accent} style={{ marginTop: 30 }} />
      ) : series.length === 0 ? (
        <Text style={{ color: AD.ink3, marginTop: 30 }}>해당 기간 데이터가 없습니다 (광고 집행/매출 없음)</Text>
      ) : (
        <DailySheet series={series} summary={summary} fmt={{ won, num, roasStr, signed, platformSpend }} />
      )}
    </ScrollView>
  );
}
