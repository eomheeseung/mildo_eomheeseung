package mildo.nice.service;

// [발췌] NiceService(약 450줄)에서 "1인 1계정" 가드 부분만 추린 것입니다.
// NICE 표준창 프로토콜/복호화 등 나머지는 생략했습니다.

/**
 * NICE 본인인증 결과 저장 시 "1인 1계정"을 강제하는 가드.
 *
 * <p><b>문제.</b> 재인증 로직이 CI 중복 검사 없이 새 계정에 CI를 저장하면, 같은 사람이 활성 계정을 여러 개
 * 가질 수 있고, 제재/차단당한 유저가 다른 소셜로 재가입하는 뒷문이 됩니다. 과거엔 기존 계정의 CI를 null로 밀어
 * 새 계정에 넘겨, <b>멀쩡히 쓰던 계정이 본인인증을 잃는(is_verified=1인데 ci=null인 모순)</b> 버그도 있었습니다.
 *
 * <p><b>해결.</b> {@code users.ci} 유니크 + 아래 가드.
 * <ul>
 *   <li>같은 CI를 <b>살아있는 다른 계정</b>이 이미 보유 → 인증 거부(DUPLICATE_CI)</li>
 *   <li>본인 재인증(같은 userId) → 통과</li>
 *   <li>탈퇴 계정이 보유 → 승계 허용 (탈퇴 시 ci=null이 되므로 보통 여기 안 걸리지만, 잔존 시 대비)</li>
 * </ul>
 */
class NiceIdentityGuard {

    // 살아있는 계정 = 미탈퇴(use_yn != 'N') AND status != WITHDRAWN
    private boolean isLiveAccount(User user) {
        return !"N".equals(user.getUseYn()) && !UserStatus.WITHDRAWN.equals(user.getStatus());
    }

    @Transactional
    public NiceVerifyResult saveVerificationResult(String email, NiceCallbackRequest request) {
        // TODO: [동시성] verifyCallback이 내부에서 NICE HTTP를 호출 → 외부 I/O가 @Transactional 안에서
        //       실행되어 DB 커넥션을 호출 내내 점유. 동시 요청 증가 시 커넥션 풀 고갈 위험.
        //       → HTTP 호출을 트랜잭션 밖으로 빼고 DB 저장만 트랜잭션으로 감싸는 구조로 분리 필요.
        NiceVerifyResult result = verifyCallback(request);
        if (!result.isVerified()) {
            return result;
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 1인 1계정 가드 — 살아있는 다른 계정이 이 CI를 가지면 인증 거부, 탈퇴 계정이면 CI 승계.
        if (result.getCi() != null && !result.getCi().isEmpty()) {
            userRepository.findByCi(result.getCi())
                    .ifPresent(existingUser -> {
                        if (existingUser.getId().equals(user.getId())) {
                            return; // 본인 재인증
                        }
                        if (isLiveAccount(existingUser)) {
                            log.warn("CI 중복 인증 차단: existingUserId={}, attemptUserId={}",
                                    existingUser.getId(), user.getId());
                            throw new CustomException(ErrorCode.DUPLICATE_CI);
                        }
                        log.info("탈퇴 계정 CI 승계: withdrawnUserId={}, newUserId={}",
                                existingUser.getId(), user.getId());
                        existingUser.setCi(null);
                        existingUser.setDi(null);
                        userRepository.save(existingUser);
                    });
        }

        // 인증 정보 저장 (CI/DI/이름/성별/생년월일 등) — 이하 생략
        user.setCi(result.getCi());
        user.setDi(result.getDi());
        user.setIsVerified(true);
        // ...
        return result;
    }
}
