package mildo.acquaintance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 지인 차단 — 번호 대조값과 양방향 판정 (발췌, 결정 §32).
 *
 * <p><b>왜 기존 암호화 번호로 비교하지 않나.</b> 회원 번호는 가입 경로마다 형식이 달랐다
 * (인증사 원본 숫자, 하이픈 형식, 소셜이 주는 「+82 10-…」). 같은 번호라도 암호문이 달라 맞지 않는다.
 * 그래서 형식을 하나로 맞춘 뒤 만든 일방향 값(HMAC)만 대조에 쓴다. 키는 암호화 키와 다른 용도 키다.</p>
 *
 * <p>저장은 목적별로 나눈다: 대조값(HMAC·인덱스) / 본인 목록 표시용 번호(복호화 가능한 암호화) / 성 한 글자.</p>
 */
public final class AcquaintanceMatch {

    private static final String HMAC = "HmacSHA256";

    /** 설정에서 주입받은 용도 키(암호화 키와 다른 값). */
    private static volatile byte[] keyBytes;

    private AcquaintanceMatch() {
    }

    static void setKey(byte[] key) {
        keyBytes = key;
    }

    /**
     * 휴대폰 번호를 {@code 010} 으로 시작하는 11자리 숫자로 맞춘다. 맞출 수 없으면 null.
     * 하이픈·공백·괄호를 빼고, {@code +82}/{@code 82} 로 시작하면 {@code 0} 으로 바꾼다.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("82") && digits.length() == 12) {
            digits = "0" + digits.substring(2);
        }
        return digits.matches("^010\\d{8}$") ? digits : null;
    }

    /** 정규화한 번호의 대조값(64자 hex). 휴대폰 형식이 아니거나 키가 없으면 null — 그 번호는 대조에서 빠진다. */
    public static String keyOf(String raw) {
        String normalized = normalize(raw);
        byte[] key = keyBytes;
        if (normalized == null || key == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 판정 — 서로 안 보여야 하는 회원 id. 양방향:
    //   ① 내가 등록한 번호의 주인(회원 번호 두 칸 — 가입 번호·본인인증 번호 — 어느 쪽이든 맞으면)
    //   ② 내 번호를 등록해 둔 회원
    // 이 집합은 홈·탐색·오늘의 추천이 모두 지나는 후보 필터의 「신고 차단」과 같은 자리에 합친다.
    // 이미 맺어진 매칭·진행 중인 대화는 건드리지 않는다(그건 기존 차단·신고의 일).
    // ---------------------------------------------------------------------------------------------

    /*
     * ① SELECT u.id FROM acquaintance_blocks b JOIN users u ON u.phone_key = b.phone_key
     *     WHERE b.user_id = :me AND u.id <> :me
     *    UNION
     *    SELECT u.id FROM acquaintance_blocks b JOIN users u ON u.verified_phone_key = b.phone_key
     *     WHERE b.user_id = :me AND u.id <> :me
     *
     * ② SELECT DISTINCT b.user_id FROM acquaintance_blocks b
     *     WHERE b.phone_key IN (:myKeys) AND b.user_id <> :me

     */
    public interface BlockQueries {
        List<Long> memberIdsBlockedBy(Long me);

        List<Long> ownerIdsBlocking(Set<String> myKeys, Long me);
    }

    public static Set<Long> blockedUserIds(BlockQueries q, Long me, String myPhone, String myVerifiedPhone) {
        Set<Long> ids = new HashSet<>(q.memberIdsBlockedBy(me));
        // 내 번호 대조값은 저장된 칼럼이 아니라 번호에서 바로 만든다 — 기존 회원의 칼럼이 아직 비어 있을 수 있다(채우기 전).
        Set<String> myKeys = new HashSet<>();
        String a = keyOf(myPhone);
        String b = keyOf(myVerifiedPhone);
        if (a != null) myKeys.add(a);
        if (b != null) myKeys.add(b);
        if (!myKeys.isEmpty()) {
            ids.addAll(q.ownerIdsBlocking(myKeys, me));
        }
        return ids;
    }

    /*
     * 묶음 등록(최대 500개) — 유니크 (user_id, phone_key) 위반을 예외로 막지 않는다.
     * 트랜잭션 안의 제약 위반 예외는 잡아도 rollback-only 가 남아 묶음 전체를 잃는다.
     *
     *   INSERT IGNORE INTO acquaintance_blocks (user_id, phone_key, phone, last_initial, memo, source, created_at)
     *   VALUES (:userId, :phoneKey, :phoneEnc, :lastInitial, :memoEnc, :source, :createdAt)
     *
     * 형식 오류·내 번호·직접 해제한 번호(해제 기록의 대조값)는 오류 없이 skipped 로 센다.
     * 응답 숫자는 「등록한 번호 수」뿐 — 그중 몇 명이 회원인지는 어디에도 내보내지 않는다.
     */
}
