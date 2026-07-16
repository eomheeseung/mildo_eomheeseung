// scripts/validate-release-env.cjs — 릴리즈 빌드 env 가드
//
// [포트폴리오 발췌] 실제 도메인은 placeholder로 치환했습니다.
//
// 왜 만들었나:
//   Expo는 .env 파일을 우선순위로 읽는데 `.env.local`이 `.env`보다 우선한다.
//   개발자가 로컬 백엔드(LAN IP·http)를 `.env.local`에 넣어두면, 릴리즈 빌드 전에
//   `.env`를 운영으로 바꿔도 `.env.local`이 덮어써서 "로컬 IP가 프로덕션 앱에 박히는"
//   사고가 난다(실제로 라이브에 http LAN URL이 나간 적이 있었다).
//
//   그래서 이 검증을 app.config.js와 Android build.gradle의 preReleaseBuild 전에 물려서,
//   "릴리즈 빌드에서 API_URL이 사설망이면 빌드 자체를 실패"시킨다. 사람의 주의력이 아니라
//   파이프라인이 오배포를 막게 만든 것.
const fs = require('fs');
const path = require('path');

const DEFAULT_API_URL = 'https://api.example.com/api';

// 릴리즈에 들어가면 안 되는 로컬/사설망 패턴
const LOCAL_API_PATTERNS = [
  /^https?:\/\/localhost(?::|\/|$)/i,
  /^https?:\/\/127\./i,
  /^https?:\/\/0\.0\.0\.0(?::|\/|$)/i,
  /^https?:\/\/10\./i,
  /^https?:\/\/192\.168\./i,
  /^https?:\/\/172\.(1[6-9]|2\d|3[0-1])\./i,
];

function parseDotEnv(filePath) {
  if (!fs.existsSync(filePath)) return {};
  return fs.readFileSync(filePath, 'utf8').split(/\r?\n/).reduce((acc, line) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) return acc;
    const match = trimmed.match(/^([\w.-]+)\s*=\s*(.*)$/);
    if (!match) return acc;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    acc[match[1]] = value;
    return acc;
  }, {});
}

// Expo와 동일한 우선순위(.env < .env.local < process.env)로 실제 해석될 값을 재현
function readEnvApiUrl(projectRoot = process.cwd()) {
  const env = {
    ...parseDotEnv(path.join(projectRoot, '.env')),
    ...parseDotEnv(path.join(projectRoot, '.env.local')),
    ...process.env,
  };
  return env.API_URL || DEFAULT_API_URL;
}

const isLocalApiUrl = (apiUrl) => LOCAL_API_PATTERNS.some((p) => p.test(apiUrl));

function shouldValidateRelease() {
  const args = new Set(process.argv.slice(2));
  return (
    args.has('--release') ||
    process.env.BUILD_PROFILE === 'production' ||
    process.env.EAS_BUILD_PROFILE === 'production' ||
    process.env.APP_ENV === 'production' ||
    process.env.NODE_ENV === 'production'
  );
}

// app.config.js(extra.apiUrl 결정)와 build.gradle(validateReleaseEnv task)에서 호출.
function validateReleaseApiUrl(options = {}) {
  const projectRoot = options.projectRoot || process.cwd();
  const apiUrl = options.apiUrl || readEnvApiUrl(projectRoot);
  const release = options.release ?? shouldValidateRelease();

  if (release && isLocalApiUrl(apiUrl)) {
    throw new Error(
      `[BUILD BLOCKED] Release build cannot use local API_URL: ${apiUrl}\n` +
      'Use a production API URL or disable .env.local before building.'
    );
  }
  return apiUrl;
}

if (require.main === module) {
  try {
    console.log(`[env-check] API_URL=${validateReleaseApiUrl()}`);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

module.exports = { DEFAULT_API_URL, isLocalApiUrl, readEnvApiUrl, shouldValidateRelease, validateReleaseApiUrl };
