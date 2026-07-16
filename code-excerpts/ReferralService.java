package tideflo.tide_match.referral.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tideflo.tide_match.auth.entity.User;
import tideflo.tide_match.auth.entity.UserStatus;
import tideflo.tide_match.auth.repository.UserRepository;
import tideflo.tide_match.ex.CustomException;
import tideflo.tide_match.ex.ErrorCode;
import tideflo.tide_match.referral.dto.ReferralHistoryResponse;
import tideflo.tide_match.referral.dto.ReferralSummaryResponse;
import tideflo.tide_match.referral.entity.Referral;
import tideflo.tide_match.referral.repository.ReferralRepository;
import tideflo.tide_match.token.entity.TokenGrantSource;
import tideflo.tide_match.token.entity.TokenTransactionType;
import tideflo.tide_match.referral.util.NicknameMasker;
import tideflo.tide_match.token.service.TokenLedgerService;
import tideflo.tide_match.token.util.CiHasher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 친구 초대(리퍼럴). 초대자 A와 피초대자 B <b>양쪽</b>에 각 50베리를 가입 완료 시점에 1회 지급한다. 초대 한도 없음(무제한).
 *
 * <p><b>어뷰징 방지 3중 방어</b>
 * <ul>
 *   <li>자기 자신 초대 금지 (userId 동일 / CI 동일 — 탈퇴 후 만든 부계정으로 자기를 초대하는 경우까지 차단)</li>
 *   <li>{@code referrals} 유니크 — invitee_id(계정 1회) + invitee_ci_hash(사람 1회, 재가입 파밍 차단)</li>
 *   <li>원장 멱등키 {@code (EARN_BONUS, REFERRAL_INVITER|REFERRAL_INVITEE, CI해시(B))} — 관계 행이 어떤 경로로
 *       중복 생성돼도 베리는 사람당 1회만 나간다. 초대자 쪽 키를 <b>B의 CI</b>로 잡는 것이 핵심 —
 *       A는 무제한이므로 A의 CI로 잡으면 두 번째 초대부터 멱등에 걸려 지급이 막힌다.</li>
 * </ul>
 * <b>본인인증(CI) 없는 계정은 보상 스킵</b> — 관계는 기록하되 rewarded=false. 무제한 정책의 안전판이다.</p>
 *
 * <p>보상 실패가 가입을 깨지 않도록 커밋 후 별도 트랜잭션에서 처리한다({@link tideflo.tide_match.referral.event.ReferralEventListener}).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    /** 초대 보상(초대자·피초대자 각각). */
    public static final int REFERRAL_REWARD = 50;

    private static final String REF_TYPE_INVITER = "REFERRAL_INVITER";
    private static final String REF_TYPE_INVITEE = "REFERRAL_INVITEE";

    private final ReferralRepository referralRepository;
    private final UserRepository userRepository;
    private final TokenLedgerService tokenLedgerService;

    /**
     * 초대 관계를 확정하고 양쪽에 보상을 지급한다. 가입 커밋 후 별도 트랜잭션에서 호출된다.
     *
     * <p>검증에 걸리면 <b>예외를 던지지 않고 스킵</b>한다 — 초대는 부가 기능이라 잘못된 inviterId 하나로
     * 가입 후처리를 실패시킬 이유가 없다(가입 자체는 이미 커밋됨).</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(Long inviteeId, Long inviterId) {
        if (inviteeId == null || inviterId == null) {
            return;
        }
        if (inviteeId.equals(inviterId)) {
            log.warn("[REFERRAL] 스킵 — 자기 자신 초대: userId={}", inviteeId);
            return;
        }
        if (referralRepository.existsByInviteeId(inviteeId)) {
            // 귀속은 1회성 고정 — 이미 초대자가 있는 계정은 갱신하지 않는다.
            log.info("[REFERRAL] 스킵 — 이미 초대 귀속됨: inviteeId={}", inviteeId);
            return;
        }

        User invitee = userRepository.findById(inviteeId).orElse(null);
        User inviter = userRepository.findById(inviterId).orElse(null);
        if (invitee == null || inviter == null) {
            log.warn("[REFERRAL] 스킵 — 유저 없음: inviteeId={}, inviterId={}", inviteeId, inviterId);
            return;
        }
        if (!isLiveAccount(inviter)) {
            log.warn("[REFERRAL] 스킵 — 초대자가 탈퇴/정지 계정: inviterId={}", inviterId);
            return;
        }

        String inviteeCi = invitee.getCi();
        String inviteeCiHash = (inviteeCi != null && !inviteeCi.isBlank()) ? CiHasher.hash(inviteeCi) : null;

        // 자기 자신 초대(CI 기준) — 부계정으로 본인을 초대하는 우회 차단.
        if (inviteeCi != null && inviteeCi.equals(inviter.getCi())) {
            log.warn("[REFERRAL] 스킵 — 동일인(CI) 자기 초대: inviteeId={}, inviterId={}", inviteeId, inviterId);
            return;
        }
        // 같은 사람이 탈퇴 후 재가입해 초대 보상을 반복 수령하는 파밍 차단.
        if (inviteeCiHash != null && referralRepository.existsByInviteeCiHash(inviteeCiHash)) {
            log.warn("[REFERRAL] 스킵 — 이미 초대 보상을 받은 사람(재가입 추정): inviteeId={}", inviteeId);
            return;
        }

        boolean rewardable = inviteeCiHash != null;
        Referral referral = referralRepository.save(Referral.builder()
                .inviterId(inviterId)
                .inviteeId(inviteeId)
                .inviteeCiHash(inviteeCiHash)
                .rewardBerry(rewardable ? REFERRAL_REWARD : 0)
                .rewarded(false)
                .build());

        if (!rewardable) {
            // 본인인증 없는 계정 — 관계만 남기고 보상은 스킵(무제한 정책의 안전판).
            log.warn("[REFERRAL] 보상 스킵 — 피초대자 본인인증(CI) 없음: inviteeId={}", inviteeId);
            return;
        }

        // 멱등키의 refId는 양쪽 모두 "B의 CI 해시" (refType으로 초대자/피초대자를 구분).
        tokenLedgerService.creditFree(inviterId, REFERRAL_REWARD, TokenGrantSource.REFERRAL,
                TokenTransactionType.EARN_BONUS, REF_TYPE_INVITER, inviteeCiHash, "친구 초대 보상");
        tokenLedgerService.creditFree(inviteeId, REFERRAL_REWARD, TokenGrantSource.REFERRAL,
                TokenTransactionType.EARN_BONUS, REF_TYPE_INVITEE, inviteeCiHash, "친구 초대 보상");

        referral.setRewarded(true);
        log.info("[REFERRAL] 초대 보상 지급 완료 — inviterId={}, inviteeId={}, 각 {}베리",
                inviterId, inviteeId, REFERRAL_REWARD);
    }

    /** 마이페이지 초대 현황. */
    @Transactional(readOnly = true)
    public ReferralSummaryResponse getMySummary(String email) {
        Long userId = getUserId(email);
        return ReferralSummaryResponse.builder()
                .invitedCount((int) referralRepository.countByInviterId(userId))
                .rewardedCount((int) referralRepository.countByInviterIdAndRewardedTrue(userId))
                .totalRewardBerry(referralRepository.sumRewardBerryByInviterId(userId))
                .build();
    }

    /** 초대 내역 목록 (최신순). 닉네임은 마스킹해서 내려준다. */
    @Transactional(readOnly = true)
    public List<ReferralHistoryResponse> getMyHistory(String email) {
        Long userId = getUserId(email);
        List<Referral> referrals = referralRepository.findByInviterIdOrderByCreatedAtDesc(userId);
        if (referrals.isEmpty()) {
            return List.of();
        }

        List<Long> inviteeIds = referrals.stream().map(Referral::getInviteeId).toList();
        Map<Long, String> nicknames = new HashMap<>();
        userRepository.findAllById(inviteeIds)
                .forEach(u -> nicknames.put(u.getId(), u.getNickname()));

        return referrals.stream()
                .map(r -> ReferralHistoryResponse.builder()
                        .nickname(NicknameMasker.mask(nicknames.get(r.getInviteeId())))
                        .joinedAt(r.getCreatedAt())
                        .rewardBerry(r.getRewardBerry())
                        .build())
                .collect(Collectors.toList());
    }

    private Long getUserId(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND))
                .getId();
    }

    private boolean isLiveAccount(User user) {
        return !"N".equals(user.getUseYn()) && !UserStatus.WITHDRAWN.equals(user.getStatus());
    }
}
