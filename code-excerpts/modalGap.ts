/**
 * 발췌: iOS 모달 간격(전체).
 *
 * iOS는 <Modal>이 닫히는 애니메이션 도중에 열리는 다른 <Modal>을 조용히 무시한다.
 * 그러면 새 창은 안 보이는데 보이지 않는 창이 화면 터치를 전부 막는다(지인 차단 「다시 불러오기」 뒤 멈춤).
 * 한 창을 닫고 바로 다른 창을 열어야 할 때는 이만큼 기다린 뒤 연다. 안드로이드는 제약이 없어 기다리지 않는다.
 */
import { Platform } from 'react-native';

export const MODAL_GAP_MS = Platform.OS === 'ios' ? 450 : 0;

export const waitModalGap = () =>
  MODAL_GAP_MS > 0 ? new Promise<void>((r) => setTimeout(r, MODAL_GAP_MS)) : Promise.resolve();
