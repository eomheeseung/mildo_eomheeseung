package mildo.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 모니터링 지표 엔드포인트의 접근 규칙 — <b>발췌</b>: {@code SecurityConfig} 에서 actuator 관련 규칙과
 * 포트 판정 헬퍼만. (CORS·JWT 필터·공개 API 목록·레이트리밋 필터는 생략)
 *
 * <p><b>배경.</b> 운영 지표(JVM·DB 커넥션 풀·API 응답시간)를 Prometheus 형식으로 내보내야 했다.
 * Actuator 는 기본적으로 서비스와 같은 포트에 붙는데, 서비스 포트는 리버스 프록시가 받는 포트다.
 * 거기에 지표를 두면 인증 규칙 하나가 서버 내부 정보를 지키는 유일한 방어선이 되고,
 * 수집기에 토큰을 발급해 설정 파일에 넣어야 한다. 그래서 설정으로 관리 포트를 분리했다.</p>
 *
 * <pre>
 * management:
 *   server:
 *     port: 8091
 *     address: 127.0.0.1      # 같은 서버 안의 수집기만 닿는다
 * </pre>
 *
 * <p><b>막힌 지점.</b> 관리 포트를 나눠도 <b>Spring Security 필터 체인은 그 포트에도 그대로 적용된다.</b>
 * 기존 규칙({@code /actuator/** 는 관리자만})이 살아 있어 수집기의 요청이 401 로 막혔다.</p>
 *
 * <p><b>하지 않은 것.</b> {@code /actuator/**} 를 통째로 {@code permitAll} 하지 않았다. 그러면 나중에 누가
 * 관리 포트 설정을 지웠을 때 actuator 가 서비스 포트로 돌아오면서 인증 없이 공개된다 —
 * 설정 한 줄이 빠진 결과가 「정보 공개」가 되는 구조다.</p>
 *
 * <p><b>한 것.</b> 규칙을 경로가 아니라 <b>요청이 들어온 포트</b>로 갈랐다. 관리 포트로 온 요청만 연다.
 * 설정이 빠지면 조건이 거짓이 되어 관리자 전용 규칙으로 떨어진다 — <b>닫힌 쪽으로 실패한다.</b></p>
 *
 * <pre>
 * 관리 포트 설정 있음 + 그 포트로 온 요청   → 인증 없이 허용 (127.0.0.1 에서만 닿는다)
 * 서비스 포트로 온 요청                    → 조건 거짓 → 관리자 전용
 * 관리 포트 설정을 누가 지움                → managementPort == null → 조건 거짓 → 관리자 전용
 * 관리 포트를 서비스 포트와 같게 설정        → 조건 거짓 → 관리자 전용
 * </pre>
 *
 * <p>{@code request.getLocalPort()} 는 요청을 <b>받은 서버 쪽</b> 포트다. 프록시가 붙이는
 * {@code X-Forwarded-Port} 헤더와 달리 클라이언트가 꾸밀 수 없다.</p>
 *
 * <p>검증은 열려야 할 곳과 닫혀야 할 곳을 둘 다 봤다 — 127.0.0.1:8091 은 200,
 * 서비스 포트의 actuator 는 401, 서버의 외부 주소로 8091 은 연결 자체가 안 된다.
 * 전체 구성은 {@code docs/monitoring-infra.md} §8.</p>
 */
@Configuration
@RequiredArgsConstructor
public class ManagementPortSecurity {

    private static final String ROLE_ADMIN = "ADMIN";

    private final Environment environment;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                // ... 공개 API 규칙 생략 ...

                // 모니터링 전용 포트(127.0.0.1 에만 묶임)로 들어온 actuator 는 인증 없이 허용한다.
                // 같은 서버 안의 수집기만 닿을 수 있는 포트라 토큰을 요구할 이유가 없고, 요구하면 지표 수집이 401 로 막힌다.
                // 서비스 포트에서는 이 조건이 절대 참이 되지 않는다 — 아래 관리자 전용 규칙이 그대로 적용된다.
                .requestMatchers(request -> isManagementPort(request.getLocalPort())
                        && request.getRequestURI().startsWith("/actuator")).permitAll()

                // 서비스 포트의 actuator — 관리자만
                .requestMatchers("/actuator/**").hasRole(ROLE_ADMIN)

                .anyRequest().authenticated()
        );
        return http.build();
    }

    /**
     * 요청이 모니터링 전용 포트로 들어왔는가. {@code management.server.port} 가 서비스 포트와 다를 때만 참이 될 수 있다
     * (설정이 없거나 같으면 actuator 는 서비스 포트에 있고 관리자 전용 규칙을 그대로 탄다).
     */
    private boolean isManagementPort(int localPort) {
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        Integer serverPort = environment.getProperty("server.port", Integer.class, 8080);
        return managementPort != null && !managementPort.equals(serverPort) && managementPort == localPort;
    }
}
