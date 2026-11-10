package mildo.aidate.service;

// 발췌: 페르소나 소개팅 생성·완료·실패의 트랜잭션 경계. 설명: docs/design/engineering-decisions.md §28
// 실제 코드의 두 클래스(생성기 · DB 쓰기 경계)를 한 파일로 모았다. import·필드 선언 일부 생략.

/**
 * 생성기 — AI 호출(수십 초)은 트랜잭션 밖(@Async)에서, DB 쓰기는 끝에 짧게만.
 */
@Service
@RequiredArgsConstructor
class AiDateGenerator {

    private final AiDateRepository aiDateRepository;
    private final PersonaRepository personaRepository;
    private final AiService aiService;
    private final AiDateTx tx;
    private final NotificationService notificationService;
    private final AiDateAccess access;

    @Async
    public void generate(Long aiDateId) {
        AiDate d = aiDateRepository.findById(aiDateId).orElse(null);
        if (d == null || !d.isGenerating()) {
            return;
        }
        Long requesterId = d.getRequesterId();
        try {
            Persona a = personaRepository.findActiveByUserId(requesterId).orElseThrow();
            Persona b = personaRepository.findActiveByUserId(d.getTargetId()).orElseThrow();

            // 점수는 대화가 아니라 두 페르소나 네 축으로 먼저 낸다. AI 는 그 톤에 맞춰 대화·문장만 쓴다.
            int score = AiDateScore.score(a.getAxisScores(), b.getAxisScores());
            Map<String, Object> result = generateChecked(a, b, score, aiDateId);

            // AI 를 기다리는 사이(수십 초) 상대가 탈퇴·차단·신고 관계가 됐으면 저장·차감·알림 없이 조용히 끝낸다.
            // 「상대 때문에 실패」 알림을 보내면 차단 사실이 드러난다.
            if (!access.canSee(requesterId, d.getTargetId())) {
                tx.markFailed(aiDateId, AiDate.FAIL_TARGET_UNAVAILABLE);
                return;
            }
            tx.complete(aiDateId, result);   // 차감 + 저장 = 한 트랜잭션
            notificationService.aiDateReady(requesterId, aiDateId);
        } catch (CustomException e) {
            boolean insufficient = e.getErrorCode() == ErrorCode.INSUFFICIENT_TOKENS;
            // complete() 가 잔액 부족으로 롤백된 뒤 — 실패 기록은 그 트랜잭션 밖에서 새로 남긴다
            fail(aiDateId, requesterId, insufficient ? AiDate.FAIL_INSUFFICIENT_TOKENS : AiDate.FAIL_AI_ERROR, insufficient);
        } catch (Exception e) {
            fail(aiDateId, requesterId, AiDate.FAIL_AI_ERROR, false);
        }
    }

    /** 거짓(페르소나에 없는 사실)이 섞이면 버리고 한 번 더. 두 번째도 섞이면 실패(미차감). */
    static final int MAX_ATTEMPTS = 2;

    private Map<String, Object> generateChecked(Persona a, Persona b, int score, Long aiDateId) {
        String tone = AiDateScore.tone(score);
        List<String> notes = AiDateScore.axisNotes(a.getAxisScores(), b.getAxisScores());   // 숫자 없이 방향만
        for (int attempt = 1; ; attempt++) {
            try {
                return AiDateResultParser.normalize(aiService.runAiDate(a, b, tone, notes), score);
            } catch (AiDateResultParser.FabricatedResultException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private void fail(Long aiDateId, Long requesterId, String reason, boolean insufficient) {
        // 만드는 도중 탈퇴로 행이 파기됐으면 알림도 보내지 않는다
        if (tx.markFailed(aiDateId, reason)) {
            notificationService.aiDateFailed(requesterId, aiDateId, insufficient);
        }
    }
}

/**
 * DB 쓰기 경계.
 *
 * <p>⚠️ 완료(차감+저장)는 한 트랜잭션이다. 차감이 잔액 부족으로 실패하면 트랜잭션이 rollback-only 가 되므로
 * 실패 기록은 <b>호출부가 이 트랜잭션 밖에서</b> 새 트랜잭션으로 남긴다.</p>
 */
@Service
@RequiredArgsConstructor
class AiDateTx {

    private final AiDateRepository aiDateRepository;
    private final TokenSpendService tokenSpendService;

    /** 결과 저장 + 과금. 첫 1회 무료 — 무료로 「완료된」 건이 아직 없으면 이 건이 무료(실패는 무료를 쓰지 않는다). */
    @Transactional
    public boolean complete(Long aiDateId, Map<String, Object> result) {
        AiDate d = aiDateRepository.findById(aiDateId).orElseThrow();
        boolean free = !aiDateRepository.existsByRequesterIdAndStatusAndFreeUsedTrue(d.getRequesterId(), AiDate.STATUS_READY);
        if (!free) {
            tokenSpendService.consumeAiDate(d.getRequesterId(), d.getId()); // 부족하면 예외 → 전체 롤백
        }
        d.setFreeUsed(free);
        d.setResult(result);
        d.setStatus(AiDate.STATUS_READY);
        d.setCompletedAt(LocalDateTime.now());
        return free;
    }

    /** 실패 기록 — 차감 없음. 완료 트랜잭션이 롤백된 뒤에도 남도록 항상 새 트랜잭션. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long aiDateId, String reason) {
        return aiDateRepository.findById(aiDateId).map(d -> {
            d.setStatus(AiDate.STATUS_FAILED);
            d.setFailReason(reason);
            d.setCompletedAt(LocalDateTime.now());
            return true;
        }).orElse(false);
    }

    /** 서버 재시작 등으로 끝나지 못한 진행 건(10분 넘음)을 실패로 — 「진행 중 1건」 제한에 영영 걸리지 않게 */
    @Transactional
    public int failStale() {
        LocalDateTime now = LocalDateTime.now();
        return aiDateRepository.failStale(now.minusMinutes(10), now);
    }
}
