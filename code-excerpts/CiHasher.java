package mildo.token.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * CI(본인인증 고유식별키)를 SHA-256 hex로 해싱한다.
 *
 * <p>1회성 무상 보상(가입 보너스·친구 초대)의 멱등키는 userId가 아니라 <b>사람</b> 기준이어야 한다.
 * userId 기준이면 탈퇴→재가입마다 새 userId가 생겨 보상이 재지급(파밍)된다. CI 해시는 탈퇴로 원 계정의 CI가
 * 파기돼도 원장(token_transaction)에 남아 같은 사람의 재수령을 막는다.
 * 원문 CI(PII)를 그대로 저장하지 않기 위해 해시로 쓴다(64자, ref_id 길이 100 이내).</p>
 *
 * <p><b>해시 방식을 바꾸면 기존 멱등키와 어긋나 과거 수령자에게 보상이 재지급된다. 변경 금지.</b></p>
 */
public final class CiHasher {

    private CiHasher() {
    }

    public static String hash(String ci) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(ci.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
