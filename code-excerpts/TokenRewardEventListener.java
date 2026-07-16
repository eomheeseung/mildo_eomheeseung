package tideflo.tide_match.token.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tideflo.tide_match.auth.entity.User;
import tideflo.tide_match.auth.repository.UserRepository;
import tideflo.tide_match.token.entity.TokenGrantSource;
import tideflo.tide_match.token.entity.TokenTransactionType;
import tideflo.tide_match.token.service.TokenLedgerService;
import tideflo.tide_match.token.util.CiHasher;

/**
 * 무상 베리 적립 이벤트 리스너.
 *
 * <p>가입/사진 등 호스트 작업이 <b>커밋된 뒤(AFTER_COMMIT)</b> <b>별도 트랜잭션(REQUIRES_NEW)</b>으로 적립한다.
 * 적립 실패가 가입/업로드를 깨지 않도록 분리.</p>
 *
 * <p><b>어뷰징 방지(사람 단위 멱등):</b> 1회성 무상 보상은 멱등키를 <b>CI 해시</b>(사람 고유)로 쓴다.
 * userId 기준이면 탈퇴→재가입(새 userId)마다 보너스가 재지급돼 파밍이 가능하다. CI 해시는 탈퇴로 원 계정 CI가
 * 파기돼도 {@code token_transaction} 행에 남아, 같은 사람이 재가입해도 {@code (type, refType, refId)} 유니크로 1회만 지급된다.
 * <b>본인인증(CI) 없는 계정은 보상 스킵</b>(NICE 필수 정책 — 미인증/테스트 계정 제외).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRewardEventListener {

    private static final int SIGNUP_BONUS = 10; // // 임시값
    private static final int PHOTO_REWARD = 10; // // 임시값

    private final TokenLedgerService tokenLedgerService;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserSignedUp(UserSignedUpEvent event) {
        creditOnce(event.userId(), SIGNUP_BONUS, TokenGrantSource.BONUS,
                TokenTransactionType.EARN_BONUS, "SIGNUP", "가입 보너스");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProfilePhotoApproved(ProfilePhotoApprovedEvent event) {
        creditOnce(event.userId(), PHOTO_REWARD, TokenGrantSource.PHOTO,
                TokenTransactionType.EARN_PHOTO, "PHOTO", "프로필 사진 등록 보상");
    }

    /**
     * 사람(CI) 단위 1회성 무상 보상. CI 해시를 멱등키로 사용 → 탈퇴/재가입 반복 파밍 차단.
     * CI 없는(미인증) 계정은 지급하지 않는다.
     */
    private void creditOnce(Long userId, int amount, TokenGrantSource source,
                            TokenTransactionType type, String refType, String description) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return;
            }
            String ci = user.getCi();
            if (ci == null || ci.isBlank()) {
                // 본인인증(CI) 없는 계정 — 보상 스킵(어뷰징/테스트 방지). NICE 완료 시 지급.
                log.warn("[REWARD] {} 스킵 — 본인인증(CI) 없음: userId={}", refType, userId);
                return;
            }
            // 멱등키 = CI 해시 (사람 고유, 탈퇴해도 token_transaction에 남아 재가입 재지급 차단)
            tokenLedgerService.creditFree(userId, amount, source, type, refType, CiHasher.hash(ci), description);
        } catch (Exception e) {
            // 적립 실패는 호스트 작업(가입/업로드)에 영향 없음. 로그만.
            log.error("[REWARD] {} 적립 실패(호스트 작업은 정상): userId={}", refType, userId, e);
        }
    }
}
