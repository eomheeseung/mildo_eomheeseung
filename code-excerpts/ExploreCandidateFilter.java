package mildo.explore.service.assembler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import mildo.auth.entity.User;
import mildo.complaint.repository.ComplaintRepository;
import mildo.explore.repository.ExploreSkipRepository;
import mildo.match.repository.MatchRepository;
import mildo.matchrequest.repository.MatchRequestRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 탐색 후보 사용자 필터링 책임
 * 스킵/신고/이미 매칭/매칭 요청 중인 사용자를 제외
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExploreCandidateFilter {

    private final ExploreSkipRepository exploreSkipRepository;
    private final ComplaintRepository complaintRepository;
    private final MatchRepository matchRepository;
    private final MatchRequestRepository matchRequestRepository;

    /**
     * 현재 사용자 기준으로 탐색 후보 리스트 필터링
     * 필터 조건: 스킵 / 신고 / 이미 매칭 / 매칭 요청(ACCEPTED/PENDING)
     */
    public List<User> filterCandidates(User currentUser, List<User> candidates) {
        Long currentUserId = currentUser.getId();

        List<Long> skippedUserIds = exploreSkipRepository.findSkippedUserIdsByUserId(currentUserId);

        List<Long> reportedUserIds = complaintRepository.findComplaintBlockedUserIds(currentUserId);
        log.info("신고 차단(양방향) 필터링: currentUserId={}, blockedUserIds={}", currentUserId, reportedUserIds);

        List<Long> matchedUserIds = matchRepository.findMatchedPartnerIds(currentUserId);

        List<Long> matchRequestPartnerIds = matchRequestRepository.findMatchRequestPartnerIds(currentUserId);
        log.info("매칭 요청 상대 필터링: currentUserId={}, matchRequestPartnerIds={}", currentUserId, matchRequestPartnerIds);
        log.info("매칭된 사용자 필터링: currentUserId={}, matchedUserIds={}", currentUserId, matchedUserIds);

        // Set 변환으로 contains O(1) lookup (List는 O(n))
        Set<Long> skippedSet = new HashSet<>(skippedUserIds);
        Set<Long> reportedSet = new HashSet<>(reportedUserIds);
        Set<Long> matchedSet = new HashSet<>(matchedUserIds);
        Set<Long> matchRequestSet = new HashSet<>(matchRequestPartnerIds);

        return candidates.stream()
                .filter(u -> !skippedSet.contains(u.getId()))
                .filter(u -> !reportedSet.contains(u.getId()))
                .filter(u -> !matchedSet.contains(u.getId()))
                .filter(u -> !matchRequestSet.contains(u.getId()))
                .toList();
    }
}
