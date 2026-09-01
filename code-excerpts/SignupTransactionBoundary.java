package mildo.auth.service;

import java.util.List;

/**
 * 회원가입 — <b>트랜잭션 경계에서 외부 호출을 걷어낸 형태.</b>
 *
 * <p><b>고치기 전.</b> {@code signupWithPersona} 하나에 {@code @Transactional} 이 붙어
 * 본인인증 외부 HTTP 와 LLM 페르소나 생성(운영 기준 40~50초)까지 통째로 감싸고 있었다.
 * JPA 에서 트랜잭션이 열리는 순간 커넥션이 확보되고 커밋까지 그 스레드가 점유하므로,
 * LLM 이 응답할 때까지 커넥션 하나가 계속 묶인다. 운영 Hikari 누수 경고(임계값 60초)가
 * <b>8/22 이후 26건 중 25건이 이 진입점</b>이었다.</p>
 *
 * <p><b>더 큰 피해는 롤백 범위였다.</b> LLM 조직 한도 장애 때 400 하나로 가입 자체가
 * 롤백돼 <b>22명이 계정도 지갑도 없이 사라졌다</b> — 본인인증까지 마친 사람들이다.
 * 여기서 롤백은 방어가 아니라 과잉이었다. 페르소나는 다시 만들면 되지만 계정은 아니다.</p>
 *
 * <p><b>측정.</b> {@code performance_schema.events_transactions_current} 를 230ms 간격으로
 * 폴링해 같은 성격의 API 를 전후 비교했다. ({@code innodb_trx} 로는 안 잡힌다 —
 * LLM 호출 전에 SELECT 만 하는 트랜잭션은 InnoDB 에 등록되지 않는다.)</p>
 *
 * <pre>
 *                 요청시간   트랜잭션 열린 샘플   최장 지속
 *   변경 전        23.0초        95 / 100        22.493초
 *   변경 후        23.2초         0 / 100         0.000초
 * </pre>
 *
 * <p><b>왜 {@code @Transactional} 을 셋으로 나누지 않았나.</b> 같은 클래스 안에서 나누면
 * <b>자기호출이라 프록시를 안 타 경계가 아예 안 생긴다.</b> 별도 빈으로 빼는 방법도 있으나
 * 그러면 이 클래스의 단위 테스트가 {@code @InjectMocks} 로 조립할 때
 * <b>검증 대상 로직까지 목으로 덮어버린다</b>(실제로 만들었다가 되돌렸다).
 * 그래서 로직은 클래스 안에 두고 경계만 {@link TransactionTemplate} 로 잡았다.</p>
 *
 * <p>부수 효과로 테스트가 쉬워진다 — 목으로 만들어 콜백을 즉시 실행하게 스텁하면,
 * 원본에는 이 필드가 아예 없으므로 <b>같은 테스트가 리팩토링 전후 양쪽에 붙는다.</b>
 * 특성화 테스트의 기준선 측정이 이 성질에 의존한다.</p>
 */
public class SignupTransactionBoundary {

    private final TransactionTemplate txTemplate;
    private final PersonaService personaService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ApplicationEventPublisher eventPublisher;

    SignupTransactionBoundary(TransactionTemplate txTemplate, PersonaService personaService,
                              JwtTokenProvider jwtTokenProvider, ApplicationEventPublisher eventPublisher) {
        this.txTemplate = txTemplate;
        this.personaService = personaService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.eventPublisher = eventPublisher;
    }

    /**
     * <b>이 메서드에는 {@code @Transactional} 이 없다.</b> 네 토막의 순서만 잡는다.
     *
     * <p>정상 흐름의 결과는 이전과 같다 — 요청·응답 형태, 예외, 저장되는 값, 발행되는 이벤트가
     * 모두 동일하다. 달라지는 것은 <b>LLM 이 실패했을 때</b>뿐이다. 예전에는 가입까지 롤백돼
     * 계정이 사라졌고, 이제는 계정이 남아 재진입 경로로 이어진다.</p>
     */
    public SignupResponse signupWithPersona(SignupRequest request) {
        // A. 가입 — 트랜잭션 1. 커밋되면서 커넥션을 반납한다.
        User savedUser = txTemplate.execute(status -> registerUser(request));

        // B. 페르소나 — 트랜잭션 밖. LLM 40~50초 동안 커넥션을 잡지 않는다.
        PersonaResponse persona = null;
        if (request.hasPersonaAnswers()) {
            persona = personaService.createFromSurvey(savedUser.getEmail(), request.toPersonaRequest());
        }

        // C. 광고 전환 이벤트 — DB 작업이 없는 짧은 트랜잭션 안에서 발행한다.
        //
        // 수신자가 @TransactionalEventListener(AFTER_COMMIT) 라 커밋할 트랜잭션이 없으면
        // 리스너가 조용히 안 불린다. 예외도 안 나고 이벤트만 증발한다.
        //
        // A(가입 커밋) 직후가 아니라 B(페르소나) 뒤에 두는 이유는 의미 때문이다.
        // 예전에는 페르소나까지 성공해야 커밋됐으므로 이 이벤트는 「온보딩 완주」를 뜻했다.
        // 앞으로 당기면 페르소나 실패자까지 전환으로 집계되는데, 외부 광고 서버로 나가는 값이라
        // 취소도 안 되고 멱등 처리도 없다. 지표가 부풀면 매체가 잘못된 신호로 최적화한다.
        Long userId = savedUser.getId();
        txTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(ConversionEvent.signupCompleted(userId)));

        // D. 토큰 발급 — DB 무관.
        return SignupResponse.of(savedUser, persona,
                jwtTokenProvider.createToken(savedUser.getEmail(), savedUser.getRole()),
                jwtTokenProvider.createRefreshToken(savedUser.getEmail()));
    }

    /**
     * 가입 본체 — 트랜잭션 구간. 유저 생성/갱신까지만 하고 커밋한다.
     *
     * <p>{@code private} 이지만 {@link TransactionTemplate} 을 통해 호출되므로 트랜잭션이 실제로
     * 열린다. 프록시 기반이 아니라 자기호출 함정이 없다.</p>
     *
     * <p>원래 251줄이었다. <b>번호를 매긴 주석 8개로 구역을 나누고 있었는데, 그게 곧 메서드가
     * 필요하다는 신호였다.</b> 줄 수가 문제가 아니라 한 메서드에 이름 붙일 일이 8개 있던 것이다.
     * 아래 7개로 나누니 본체는 절차만 남았다. 특히 {@code resolveSignupProfile} 81줄은
     * DB 를 한 번도 안 만지는데, 그 사실이 이제 시그니처만 봐도 보인다.</p>
     */
    private User registerUser(SignupRequest request) {
        validateEmailDomain(request.getEmail());                             // 0. 도메인 화이트리스트

        IdentityResult identity = resolveIdentityVerification(request);      // 1. 본인인증 조회
        User existingUser = findExistingUser(request, identity);             // 2~3. CI 우선, 없으면 이메일
        assertPhoneNotTaken(request, existingUser);                          // 4. 전화번호 중복
        SignupProfile profile = resolveSignupProfile(request, identity, existingUser);
                                                                             // 5. 폴백·미성년·필수값·정규화
        User user = buildOrUpdateUser(request, existingUser, profile);       // 6. 엔티티 생성/갱신
        applyIdentityVerification(user, identity, existingUser);             // 7. 인증정보 반영

        User savedUser = userRepository.save(user);

        // 재화 적립은 커밋 후 별도 트랜잭션에서 처리한다(AFTER_COMMIT + REQUIRES_NEW).
        // 적립 실패가 가입을 롤백시켜서는 안 되기 때문이다.
        eventPublisher.publishEvent(new UserSignedUpEvent(savedUser.getId()));
        return savedUser;
    }

    /**
     * 5단계 — 가입 프로필 확정. <b>DB 를 만지지 않는다.</b>
     *
     * <p>폴백 우선순위(요청 값 → 본인인증 값 → 기존 유저 값)를 정하고, 미성년 차단·필수값 검사·
     * 성별 정규화를 한다. 순수 계산이라 단위 테스트가 스프링 컨텍스트 없이 돈다.</p>
     *
     * <p>기존 유저 값을 참조하므로 <b>DB 조회 앞으로는 옮길 수 없다.</b> 파라미터로 받는 형태여야
     * 하는 이유다 — "순수하니까 맨 앞으로 빼자"가 항상 되는 게 아니다.</p>
     */
    private SignupProfile resolveSignupProfile(SignupRequest request, IdentityResult identity, User existingUser) {
        // ... 요청 → 인증 → 기존 유저 순으로 name/gender/birthDate/phone 채움
        // ... 만 19세 미만 차단, 이름·성별 필수 검사, 성별 코드 정규화(M/1 → MALE 등)
        return SignupProfile.of(/* name, gender, birthDate, phone, nickname, oauthId */);
    }

    // ── 아래는 발췌에서 생략 ──────────────────────────────────────────────
    private void validateEmailDomain(String email) { }
    private IdentityResult resolveIdentityVerification(SignupRequest request) { return null; }
    private User findExistingUser(SignupRequest request, IdentityResult identity) { return null; }
    private void assertPhoneNotTaken(SignupRequest request, User existingUser) { }
    private User buildOrUpdateUser(SignupRequest request, User existingUser, SignupProfile profile) { return null; }
    private void applyIdentityVerification(User user, IdentityResult identity, User existingUser) { }

    private UserRepository userRepository;

    // 타입 스텁 (발췌용)
    interface TransactionTemplate {
        <T> T execute(TransactionCallback<T> action);
        void executeWithoutResult(java.util.function.Consumer<Object> action);
    }
    interface TransactionCallback<T> { T doInTransaction(Object status); }
    interface ApplicationEventPublisher { void publishEvent(Object event); }
    interface UserRepository { User save(User user); }
    interface PersonaService { PersonaResponse createFromSurvey(String email, Object request); }
    interface JwtTokenProvider { String createToken(String email, String role); String createRefreshToken(String email); }
    static class User { Long getId() { return null; } String getEmail() { return null; } String getRole() { return null; } }
    static class SignupRequest { String getEmail() { return null; } boolean hasPersonaAnswers() { return false; } Object toPersonaRequest() { return null; } }
    static class SignupResponse { static SignupResponse of(User u, PersonaResponse p, String a, String r) { return null; } }
    static class PersonaResponse { }
    static class IdentityResult { }
    static class SignupProfile { static SignupProfile of() { return null; } }
    static class ConversionEvent { static ConversionEvent signupCompleted(Long userId) { return null; } }
    static class UserSignedUpEvent { UserSignedUpEvent(Long userId) { } }
}
