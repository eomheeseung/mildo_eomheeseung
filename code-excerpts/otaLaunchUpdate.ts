/**
 * 무선 업데이트(OTA)를 "다음 실행"이 아니라 "지금 켠 이 실행"에 적용한다
 *
 * 기본 동작과 무엇이 다른가
 *  expo-updates 의 기본은 「백그라운드로 받아두고 **다음 실행**에 적용」이다.
 *  카톡처럼 하루에 몇 번씩 켜는 앱이면 이 지연이 안 느껴진다. 그러나 이 앱은
 *  **하루 한 번 켜는 패턴**이라 "다음 실행"이 사실상 내일이다.
 *  매일 바뀌는 콘텐츠(그날의 질문·미션·추천)를 붙인 뒤로는, 새 콘텐츠가
 *  항상 하루 늦게 도착하는 구조적 손해가 됐다.
 *
 * 왜 fallbackToCacheTimeout 을 키우지 않았나
 *  그 값을 올리면 **받을 게 없어도** 매 실행이 그만큼 느려진다.
 *  하루 한 번 켜는 앱에서 매번 스플래시가 길어지는 건 손해가 더 크다.
 *  여기서는 확인은 빠르게 하고(보통 1초 미만), **받을 게 있을 때만** 기다린다.
 *
 * 왜 상한을 두었나 — 이게 이 함수의 핵심이다
 *  네트워크는 "끊긴다"보다 "안 끊기고 매달린다"가 더 나쁘다.
 *  로그인 페이지를 띄우는 공용 와이파이가 대표적으로, 연결이 10초 넘게 걸린다.
 *  그래서 확인·다운로드를 **합쳐서** 상한을 걸고, 넘으면 결과와 무관하게 앱을 띄운다.
 *  완전 오프라인은 대개 1초 안에 실패하므로 상한을 다 쓰지 않는다.
 *
 * 왜 콜드 스타트 전용인가
 *  포그라운드 복귀에도 적용하면 반영이 더 빨라지지만, 글을 쓰던 중이거나
 *  대화 중에 화면이 다시 뜨면 **쓰던 내용이 날아간다.**
 *  "빠른 반영"과 "작업 중 데이터"를 저울질해 후자를 택했다.
 *  스플래시 뒤에서 교체하면 사용자에게는 로딩이 조금 길었던 것으로만 보인다.
 *
 * 검증
 *  이 동작은 개발 빌드에서 확인할 수 없다(expo-updates 가 비활성).
 *  그래서 앱 버전 옆에 붙여둔 적용 시각 표기를 판정에 쓴다 —
 *  발행 후 **첫 실행에서** 그 값이 바뀌면 성공이다. → engineering-decisions #6
 */

import { Platform } from 'react-native';

let Updates: any = null;
if (Platform.OS !== 'web') {
  try {
    Updates = require('expo-updates');
  } catch {
    // 모듈이 없어도 앱은 정상 동작해야 한다 — 이 기능만 생략된다
  }
}

/** 확인 + 다운로드를 합친 상한. 3초는 LTE 에서 자주 못 끝내 "기다렸는데 적용 안 됨"이 된다. */
const LAUNCH_UPDATE_TIMEOUT_MS = 5000;

export async function applyPendingUpdateAtLaunch(): Promise<void> {
  try {
    if (!Updates || Updates.isEnabled !== true) return;   // 개발 빌드·웹은 대상 아님

    const result = await Promise.race([
      (async () => {
        const res = await Updates.checkForUpdateAsync();
        if (!res?.isAvailable) return 'none' as const;
        await Updates.fetchUpdateAsync();
        return 'fetched' as const;
      })(),
      new Promise<'timeout'>((resolve) =>
        setTimeout(() => resolve('timeout'), LAUNCH_UPDATE_TIMEOUT_MS),
      ),
    ]);

    // 상한 안에 **다 받았을 때만** 교체한다.
    // 'timeout' 이어도 다운로드는 백그라운드로 계속되고, 기본 동작대로 다음 실행에 적용된다.
    // 반쯤 받은 번들이 적용되는 일은 없다(다 받아야 교체).
    if (result === 'fetched') await Updates.reloadAsync();
  } catch {
    // 오프라인·서버 오류·중단 — 전부 무시하고 그냥 앱을 띄운다. 다음 실행에 다시 시도한다.
    // 업데이트 확인 실패가 앱 진입을 막는 일은 없어야 한다.
  }
}

// 진입점에서는 스플래시가 떠 있는 동안 이 함수를 먼저 끝낸 뒤 초기화를 진행한다.
// 교체가 일어나면 앱이 어차피 다시 뜨므로, 무거운 초기화를 뒤로 두면 중복 실행을 피할 수 있다.
//
//   applyPendingUpdateAtLaunch().finally(() => {
//     initI18n().then(() => setAppReady(true));
//   });
