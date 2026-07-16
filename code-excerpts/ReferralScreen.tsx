// src/screens/ReferralScreen.tsx — 친구 초대 화면 (발췌)
//
// [포트폴리오 발췌] 민감정보 없음.
//
// 화면 컴포넌트 설계 포인트
//  · 진입 시 현황(초대 수·받은 보상)과 내역을 병렬 조회, 서버 응답만 신뢰.
//  · 공유는 플랫폼마다 다르다: 네이티브는 OS 공유시트(Share), 웹은 공유 API가 없으면
//    클립보드 복사로 폴백(보안 컨텍스트 대응은 copyText가 처리). 한 핸들러에서 분기.
//  · 초대 링크는 내 userId를 심어 생성(analytics.generateReferralLink) → 받는 사람 가입 시 귀속.
import React, { useEffect, useState, useCallback } from 'react';
import { View, Text, TouchableOpacity, Share, Platform, ActivityIndicator } from 'react-native';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { generateReferralLink } from '../services/analytics';
import { copyText } from '../utils/platformUtils';

const REWARD_BERRY = 50; // 초대 성공 시 나·친구 각각

type Stats = { invitedCount: number; rewardedCount: number; totalRewardBerry: number };
type HistoryItem = { nickname: string; joinedAt: string; rewardBerry: number };

export default function ReferralScreen() {
  const { user } = useAuth();
  const [stats, setStats] = useState<Stats | null>(null);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [sharing, setSharing] = useState(false);

  // 현황 + 내역 병렬 조회 (서버가 진실 소스)
  useEffect(() => {
    (async () => {
      try {
        const [me, hist] = await Promise.all([apiClient.getReferralMe(), apiClient.getReferralHistory()]);
        if (me.success && me.data) setStats(me.data as Stats);
        if (hist.success && Array.isArray(hist.data)) setHistory(hist.data as HistoryItem[]);
      } catch {
        // 미응답 → 0/빈 내역 유지 (화면은 안 깨짐)
      }
    })();
  }, []);

  const handleShare = useCallback(async () => {
    const uid = user?.id;
    if (!uid || sharing) return;
    setSharing(true);
    const link = await generateReferralLink(uid); // <ONELINK>?inviter={내 id}
    const message = `mildo에서 함께 시작해요! 이 링크로 가입하면 서로 ${REWARD_BERRY} 베리를 받아요.\n${link}`;
    try {
      if (Platform.OS === 'web') {
        // 웹: 공유 API 있으면 그걸로, 없으면 클립보드 복사(보안컨텍스트 폴백은 copyText가 처리)
        const nav: any = typeof navigator !== 'undefined' ? navigator : null;
        if (nav?.share) await nav.share({ text: message, url: link });
        else {
          const ok = await copyText(link);
          window.alert(ok ? `초대 링크가 복사되었습니다:\n${link}` : `초대 링크(수동 복사):\n${link}`);
        }
      } else {
        await Share.share({ message }); // 네이티브 OS 공유시트
      }
    } catch {
      // 공유 취소/실패는 무시
    } finally {
      setSharing(false);
    }
  }, [user?.id, sharing]);

  return (
    <View style={{ flex: 1, padding: 20 }}>
      {/* 히어로 */}
      <View style={{ backgroundColor: '#FFF1F6', borderRadius: 20, padding: 28, alignItems: 'center' }}>
        <Text style={{ fontSize: 22, fontWeight: '700', color: '#DD246A', textAlign: 'center' }}>
          친구 초대하고{'\n'}서로 {REWARD_BERRY} 베리 받기
        </Text>
      </View>

      {/* 현황 — 로딩 전엔 '-' */}
      <View style={{ flexDirection: 'row', gap: 12, marginTop: 16 }}>
        <Stat label="초대한 친구" value={stats ? stats.invitedCount : '-'} />
        <Stat label="받은 베리" value={stats ? stats.totalRewardBerry : '-'} accent />
      </View>

      {/* 공유 버튼 */}
      <TouchableOpacity onPress={handleShare} disabled={sharing}
        style={{ height: 54, borderRadius: 15, marginTop: 20, alignItems: 'center', justifyContent: 'center', backgroundColor: '#DD246A', opacity: sharing ? 0.7 : 1 }}>
        {sharing ? <ActivityIndicator color="#fff" /> : <Text style={{ color: '#fff', fontWeight: '700', fontSize: 16 }}>초대 링크 공유하기</Text>}
      </TouchableOpacity>

      {/* 내역 — 있을 때만 */}
      {history.length > 0 && (
        <View style={{ marginTop: 32 }}>
          <Text style={{ fontSize: 18, fontWeight: '700' }}>초대 내역</Text>
          {history.map((h, i) => (
            <View key={`${h.nickname}-${i}`} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 14, borderBottomWidth: 1, borderBottomColor: '#EDEDED' }}>
              <Text>{h.nickname}</Text>
              <Text style={{ color: h.rewardBerry > 0 ? '#DD246A' : '#9E9EA5', fontWeight: '700' }}>
                {h.rewardBerry > 0 ? `+${h.rewardBerry}` : '-'}
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

function Stat({ label, value, accent }: { label: string; value: number | string; accent?: boolean }) {
  return (
    <View style={{ flex: 1, borderRadius: 14, paddingVertical: 22, alignItems: 'center', backgroundColor: '#F7F8FA' }}>
      <Text style={{ fontSize: 26, fontWeight: '700', color: accent ? '#DD246A' : '#111' }}>{value}</Text>
      <Text style={{ fontSize: 14, marginTop: 6, color: '#5F5866' }}>{label}</Text>
    </View>
  );
}
