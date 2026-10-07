// src/utils/splashGate.ts
//
// 「스플래시가 끝났는가」를 앱 어디서나 읽기 위한 작은 신호.
// 스플래시(AnimatedSplash)도 <Modal> 이라, 그게 떠 있는 동안 다른 <Modal> 을 present 하면
// **iOS 가 조용히 무시**하고 visible 은 true 그대로라 스플래시가 사라져도 재시도하지 않는다.
// → 안 보이는 모달이 화면을 막아 「팝업은 안 뜨고 홈이 멈춘」 상태가 된다(안드로이드는 겹쳐 띄워서 정상).
// _layout 의 UpdateGateModal·ProfileViewModal 은 prop 으로 받지만, 화면 안에 있는 모달(홈 공지 팝업 등)은
// 그 state 에 닿지 않아 여기서 읽는다.
//
// ★모달은 마운트·요청은 그대로 받고 **그리기만** 이 값이 true 가 된 뒤로 미룰 것.
import { useEffect, useState } from 'react';

let splashDone = false;
const listeners = new Set<(v: boolean) => void>();

/** app/_layout 이 스플래시 종료 시 1회 호출 */
export function markSplashDone() {
  if (splashDone) return;
  splashDone = true;
  listeners.forEach((fn) => { try { fn(true); } catch { /* 무시 */ } });
}

export function isSplashDone() {
  return splashDone;
}

export function useSplashDone(): boolean {
  const [done, setDone] = useState(splashDone);
  useEffect(() => {
    if (splashDone) { setDone(true); return; }
    listeners.add(setDone);
    return () => { listeners.delete(setDone); };
  }, []);
  return done;
}
