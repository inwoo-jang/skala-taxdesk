package com.inwoo.taxdesk.web;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 7 — 인증과 인가.
 *
 * <p>여기서 로그인한 계정이 곧 도구의 {@code userId} 가 된다. 계좌 소유자 격리의 출발점이다.
 * {@code /admin/**} 는 ADMIN 역할만 — 승인 게이트가 프롬프트가 아니라 필터 체인에 걸려 있어야 한다.
 *
 * <h2>권한을 두 축으로 나눈다</h2>
 *
 * 역할(USER/ADMIN)만으로는 부족하다. <b>채널</b>이 더 중요한 축이다.
 *
 * <table>
 *   <tr><th></th><th>도구(Tool) 채널</th><th>REST 채널</th></tr>
 *   <tr><td>USER</td><td>내 금융소득 · 내 신청 내역</td><td>{@code /chat/**}</td></tr>
 *   <tr><td>ADMIN</td><td><b>도구를 통한 전체 조회 없음</b></td><td>{@code /admin/**} 승인·거절·인제스트</td></tr>
 * </table>
 *
 * <p><b>ADMIN 에게도 "전체 금융소득 조회" 도구를 주지 않는다.</b> 이유가 둘이다.
 * <ol>
 *   <li><b>도구는 LLM 이 호출한다.</b> ADMIN 세션에 프롬프트 인젝션이 한 번 성공하면
 *       전체 고객 데이터가 샌다. 역할로 막아도 채널이 열려 있으면 뚫린다</li>
 *   <li><b>업무상 필요가 없다.</b> ADMIN 의 일은 절세상품 자격 확인이고, 자격은
 *       <b>서류로</b> 판정한다(조특법 §88의2③). 고객의 이자·배당 내역을 볼 이유가 없다</li>
 * </ol>
 *
 * <p>ADMIN 은 USER 권한을 <b>상속하지 않는다.</b> admin 계정으로 {@code /chat} 에 들어와
 * 남의 계좌를 묻는 경로 자체를 없앤다 (레드팀 R2 가 이걸 노린다).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // 브라우저 폼이 아니라 API 다. Basic 인증이라 CSRF 토큰을 쓸 자리가 없다.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // 데모·문서 화면과 기동 확인은 공개. 그 외에는 전부 인증이다.
                        .requestMatchers("/", "/index.html", "/console.html", "/favicon.ico").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // 지표에도 이용 패턴이 드러난다. 공개하지 않는다.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // 승인 게이트가 프롬프트가 아니라 여기에 있다.
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/chat/**", "/calc/**").hasRole("USER")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * 실습용 계정. 계정 하나가 계산 엔진의 분기 하나를 담당한다 — docs/QUESTIONS.md §1.
     *
     * <p>{@code {noop}} 은 평문 비밀번호를 그대로 쓴다는 표시다. 실습이라 이렇게 두지만
     * 실제 서비스라면 당연히 해시를 저장한다.
     */
    @Bean
    UserDetailsService users(TaxDeskProperties props) {
        List<UserDetails> all = new ArrayList<>();
        props.users().forEach((id, pw) ->
                all.add(User.withUsername(id).password("{noop}" + pw).roles("USER").build()));
        props.admins().forEach((id, pw) ->
                all.add(User.withUsername(id).password("{noop}" + pw).roles("ADMIN").build()));
        return new InMemoryUserDetailsManager(all);
    }
}
