// src/api/client.ts — API 클라이언트 (발췌)
//
// [포트폴리오 발췌] 실제 엔드포인트/도메인은 제거·일반화했습니다. base URL은 env.ts가 런타임에 해석.
//
// 설계 포인트
//  · 모든 응답을 { success, data, message } 한 형태로 정규화 → 화면은 res.success / res.data만 본다.
//  · 요청 인터셉터: 공개 API(로그인/회원가입 등)엔 토큰을 붙이지 않는다.
//  · 응답 인터셉터: 401이면 refresh로 토큰 갱신 후 "원래 요청을 그대로 1회 재시도" → 화면 로직은
//    토큰 만료를 신경 쓸 필요가 없다. 갱신도 실패하면 토큰을 비워 로그인 화면으로 흐르게 한다.
import axios, { AxiosError, AxiosInstance } from 'axios';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from '../config/env';
import { logAnalyticsEvent } from '../services/analytics';

class ApiClient {
  private client: AxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: API_BASE_URL,
      timeout: 180_000, // AI 응답 대기(최대 3분)
      headers: { 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/json; charset=utf-8' },
    });

    // 요청 인터셉터 — 공개 API엔 토큰 미첨부
    this.client.interceptors.request.use(async (config) => {
      const isPublic =
        config.url?.includes('/auth/login') ||
        config.url?.includes('/auth/signup') ||
        config.url?.includes('/public/');
      if (!isPublic) {
        const token = await AsyncStorage.getItem('access_token');
        if (token) config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });

    // 응답 인터셉터 — 401 → refresh → 원요청 재시도
    this.client.interceptors.response.use(
      (res) => res,
      async (error: AxiosError) => {
        if (error.response?.status === 401) {
          const refreshToken = await AsyncStorage.getItem('refresh_token');
          if (refreshToken) {
            try {
              const refreshed = await this.refreshToken(refreshToken);
              if (refreshed && error.config) {
                error.config.headers.Authorization = `Bearer ${refreshed.accessToken}`;
                return this.client.request(error.config); // 갱신된 토큰으로 원요청 그대로 재시도
              }
              await this.clearTokens(); // 갱신 실패 → 세션 종료
            } catch {
              await this.clearTokens();
            }
          }
        }
        return Promise.reject(error);
      },
    );
  }

  // refresh는 인터셉터를 안 타는 별도 axios로 호출(무한 루프 방지)
  private async refreshToken(refreshToken: string) {
    try {
      const res = await axios.post(`${API_BASE_URL}/v1/auth/refresh`, { refreshToken });
      const { accessToken, refreshToken: newRefresh } = res.data.data;
      await this.saveTokens(accessToken, newRefresh);
      return { accessToken, refreshToken: newRefresh };
    } catch {
      return null;
    }
  }

  async saveTokens(access: string, refresh: string) {
    await AsyncStorage.multiSet([['access_token', access], ['refresh_token', refresh]]);
  }
  async clearTokens() {
    await AsyncStorage.multiRemove(['access_token', 'refresh_token']);
  }

  // 응답 정규화 래퍼 — 모든 GET/POST가 { success, data, message } 로 통일된 형태를 돌려준다.
  private async get(path: string, params?: Record<string, any>) {
    try {
      const res = await this.client.get(path, { params });
      return { success: res.data.success, data: res.data.data, message: res.data.message };
    } catch (error: any) {
      return this.handleError(error);
    }
  }
  private async post(path: string, data?: Record<string, any>) {
    try {
      const res = await this.client.post(path, data);
      return { success: res.data.success, data: res.data.data, message: res.data.message };
    } catch (error: any) {
      return this.handleError(error);
    }
  }

  // 에러도 같은 envelope로 — 화면은 항상 res.success만 보면 된다. code(예: 중복 인증)까지 함께 넘긴다.
  private handleError(error: any) {
    return {
      success: false,
      data: null,
      message: error.response?.data?.message || error.message || '네트워크 오류가 발생했습니다',
      code: error.response?.data?.code ?? null,
      status: error.response?.status ?? null,
    };
  }

  // ── 예시 도메인 메서드 ──
  // 가입: 약관(agreements)·초대자(inviterId)를 body에 함께 실어 보낸다(계정 생성 전엔 별도 API를 못 쓰므로).
  async signupWithPersona(data: {
    email: string;
    authProvider?: string;
    personaAnswers?: Record<string, any>;
    agreements?: Array<{ agreementType: string; version: string; agreed: boolean }>;
    inviterId?: number; // 잘못돼도 백엔드가 조용히 무시 → 검증 없이 실음
  }) {
    const res = await this.post('/v1/auth/signup-with-persona', data);
    if ((res as any)?.success) void logAnalyticsEvent('complete_registration', {}); // 광고 퍼널
    return res;
  }
}

export const apiClient = new ApiClient();
export default apiClient;
