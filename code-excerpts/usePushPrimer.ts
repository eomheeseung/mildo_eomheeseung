// ─────────────────────────────────────────────────────────────────────────────
// 프리퍼미션(pre-permission) 훅 — "알림이 필요해지는 순간"에만 권한을 권한다 (발췌)
//
// 왜 만들었나:
//   가입 직후에 OS 권한 팝업을 띄우고 있었습니다. 그 시점의 사용자는 이 앱이 무엇을 보내줄지
//   아직 모릅니다. iOS는 시스템 팝업이 기기당 사실상 1회라, 그 한 번을 맥락 없이 소진하면
//   남은 회복 수단이 "설정에 들어가 직접 켜세요"뿐입니다.
//
//   그래서 팝업을 **앱이 설명한 화면 뒤로** 옮겼습니다. 매칭 신청처럼 "상대의 응답을 기다리는"
//   상태가 되는 순간에만 권합니다. 그 지점의 사용자는 알림이 왜 필요한지 이미 알고 있습니다.
//
// 규칙 3가지:
//   ① 이미 허용(granted)이면 아무것도 안 한다
//   ② undetermined = 'ask'(설명 후 OS 팝업) / denied = 'settings'(OS가 재요청을 막으므로 설정 유도)
//   ③ 한 번 보여주면 REPROMPT_DAYS 동안 다시 안 띄운다 — 신청할 때마다 뜨면 그게 더 큰 이탈이다
// ─────────────────────────────────────────────────────────────────────────────

import { useCallback, useEffect, useRef, useState } from 'react';
import { Linking } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import pushService from '../services/pushService';
import type { PrimerMode } from '../components/PushPrimerModal'; // 'ask' | 'settings'

// ★재노출 억제는 **모드별로 따로** 센다.
//   키를 공유하면 ask(요청 권유)에서 찍은 도장이, 그 직후 거부했을 때 떠야 할
//   settings(설정 유도) 모달까지 7일간 막는다 — 거부 직후가 회수 기회인데 그때를 놓친다.
//   교차 플랫폼 검증에서 "거부 후 안내가 안 뜬다"로 잡힌 결함이다.
const K_LAST_SHOWN = (mode: PrimerMode) => `push.primer.lastShownAt.${mode}`;
const REPROMPT_DAYS = 7;

// 완료 토스트(2초)와 겹치지 않게 조금 뒤에 띄운다.
// ★iOS는 앞 모달이 닫히는 중에 다음 모달을 열면 전환이 겹쳐 한 번 튄다 — 이 지연이 그것도 같이 피한다.
const SHOW_DELAY_MS = 2000;

export function usePushPrimer() {
  const [visible, setVisible] = useState(false);
  const [mode, setMode] = useState<PrimerMode>('ask');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  const maybePrompt = useCallback(async () => {
    const status = await pushService.getPermissionStatus();
    if (status === 'granted' || status === 'unsupported') return;

    // ★모드를 먼저 정하고, 그 모드의 키로 스누즈를 검사한다.
    //   순서가 반대면 위 주석의 문제가 생긴다 — 검사를 먼저 하면 어느 키로 볼지 알 수 없어
    //   공용 키를 쓰게 되고, 그 순간 ask의 도장이 settings를 막는다.
    const next: PrimerMode = status === 'undetermined' ? 'ask' : 'settings';

    const last = await AsyncStorage.getItem(K_LAST_SHOWN(next)).catch(() => null);
    if (last && Date.now() - Number(last) < REPROMPT_DAYS * 24 * 60 * 60 * 1000) return;

    setMode(next);
    // "띄우기로 결정한 시점"에 기록한다 — 사용자가 앱을 강제 종료해도 다시 조르지 않게.
    await AsyncStorage.setItem(K_LAST_SHOWN(next), String(Date.now())).catch(() => {});

    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setVisible(true), SHOW_DELAY_MS);
  }, []);

  const onCancel = useCallback(() => setVisible(false), []);

  const onConfirm = useCallback(async () => {
    setVisible(false);
    if (mode === 'ask') {
      // 여기가 OS 팝업을 띄우는 **유일한 지점**이다(설명에 동의한 사람에게만).
      await pushService.registerForPushNotifications({ requestPermission: true });
      // 허용으로 바뀌었으면 서버 보고도 갱신해 둔다(도달률 계측용)
      pushService.reportPermissionStatus();
    } else {
      Linking.openSettings();
    }
  }, [mode]);

  return { primerProps: { visible, mode, onConfirm, onCancel }, maybePrompt };
}

export default usePushPrimer;

// 쓰는 법 — 화면에서:
//   const { primerProps, maybePrompt } = usePushPrimer();
//   ... 매칭 신청 성공 후: maybePrompt();
//   ... 렌더 끝에: <PushPrimerModal {...primerProps} />
