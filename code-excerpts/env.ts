// src/config/env.ts — API base URL·환경 판별 단일 소스(single source of truth)
//
// [포트폴리오 발췌] 실제 도메인은 api.example.com 등 placeholder로 치환했습니다.
//
// 핵심 설계: 하나의 코드베이스가 웹(브라우저 도메인 자동 추종) + 네이티브(빌드에 구운 값)
// 두 런타임을 동시에 지원한다. 어느 경로에서도 "실수로 개발 서버 주소가 프로덕션에
// 나가는 것"을 막기 위해, 폴백은 반드시 운영으로 향하게(fail-safe) 설계했다.
import { Platform } from 'react-native';
import Constants from 'expo-constants';

// 운영 API. env 누락 시에도 절대 LAN/사설 IP로 떨어지지 않게 하는 최종 폴백.
export const PROD_API_URL = 'https://api.example.com/api';

// 네이티브 빌드에 구워진 값(app.config.js extra.apiUrl). 없으면 운영으로 fail-safe.
const NATIVE_API_URL = Constants.expoConfig?.extra?.apiUrl || PROD_API_URL;

// "scheme://host[:port]/path" → host[:port] → host
const extractHost = (url: string): string => url.replace(/^https?:\/\//, '').replace(/\/.*$/, '');
const extractHostname = (url: string): string => extractHost(url).split(':')[0];

// 사설/로컬 host 판별(오배포 감지용): localhost / 127.* / 10.* / 192.168.* / 172.16~31.*
const isPrivateHost = (host: string): boolean =>
  host === 'localhost' ||
  host === '127.0.0.1' ||
  /^10\./.test(host) ||
  /^192\.168\./.test(host) ||
  /^172\.(1[6-9]|2\d|3[01])\./.test(host);

// 실제 사용할 base URL 결정.
//  · 웹: 접속 host 기준 — 로컬/LAN이면 로컬 백엔드 직결, 운영 도메인이면 "현재 오리진" 그대로.
//    → dev/prod 도메인을 코드에 하드코딩하지 않고 브라우징 중인 도메인을 그대로 따라간다.
//      (mixed-content·CORS 걱정 없이 dev.example.com / example.com 어디서 열든 자동으로 맞는 API에 붙음)
//  · 네이티브: 빌드에 구워진 값(없으면 운영).
export const resolveApiBaseUrl = (): string => {
  if (Platform.OS === 'web') {
    const host = typeof window !== 'undefined' ? window.location.hostname : 'localhost';
    const isLocal = host === 'localhost' || host === '127.0.0.1' || /^[0-9.]+$/.test(host);
    if (isLocal) return `http://${host}:8080/api`;
    // 운영 도메인 → 현재 오리진 그대로(https). nginx가 /api를 백엔드로 프록시.
    return `${typeof window !== 'undefined' ? window.location.origin : PROD_API_URL}/api`;
  }
  return NATIVE_API_URL;
};

export const API_BASE_URL = resolveApiBaseUrl();

// 스토어(릴리즈) 빌드인데 사설/로컬 IP에 붙어있으면 오배포(🚩). 개발 중(__DEV__)엔 LAN 정상이라 false.
// → 앱 안에서도 "지금 잘못된 서버에 붙어있음"을 감지해 배지로 표시할 수 있게 한 방어선.
export const isLikelyMisconfigured = (): boolean => {
  if (__DEV__) return false;
  return isPrivateHost(extractHostname(API_BASE_URL));
};
