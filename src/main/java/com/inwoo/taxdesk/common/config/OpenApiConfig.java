package com.inwoo.taxdesk.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI 문서. <a href="http://localhost:8080/swagger-ui.html">/swagger-ui.html</a>
 *
 * <p>고객 화면은 {@code /index.html} 이고, Swagger 는 <b>검증·시연용</b>이다.
 * 계정을 바꿔가며 같은 질문을 던져 보는 소유자 격리 실습은 여기서 한다.
 *
 * <p>주의: 이 선언은 <b>문서일 뿐</b>이다. 실제 접근 통제는 {@code SecurityConfig} 의 필터 체인이 한다.
 */
@Configuration
public class OpenApiConfig {

    /** SecurityRequirement 와 Components 에서 같은 이름을 써야 연결된다. */
    public static final String BASIC_AUTH = "basicAuth";

    @Bean
    public OpenAPI taxDeskOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SKALA증권 세무상담 데스크 — 금융소득 종합과세 예상 안내")
                        .version("v1")
                        .description("""
                                ## 시작 순서
                                1. **POST /admin/ingest** — 세법 요약 문서를 색인한다.
                                   인메모리 스토어라 재시작하면 다시 해야 한다.
                                2. 오른쪽 위 **Authorize** 버튼에서 로그인한다.
                                3. **POST /chat** 으로 상담을 시작한다. (고객 화면은 `/index.html`)

                                ## 설계 한 줄
                                LLM 에게 산수를 시키지 않는다. 근거(RAG · 조문 인용)와
                                계산(Tool · 전개 과정)을 둘 다 응답에 실어 검증 가능하게 만든다.

                                ## 이 서비스가 하지 않는 것
                                - 양도·사업·부동산임대·퇴직소득 안내 → 업무 범위 밖
                                - 타사 계좌 조회 → 권한 없음. 금액을 입력받아 합산만 한다
                                - 신고 대리 → 세무사법 §2. 제휴 세무사 상담 예약까지만

                                거절도 설계다. `POST /chat` 에 "양도세 얼마예요?" 를 넣고
                                `route` 필드와 `tokens` 를 함께 보라 — 주 모델을 부르지 않는다.
                                """))
                .components(new Components()
                        .addSecuritySchemes(BASIC_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("""
                                        HTTP Basic 인증. 여기서 로그인한 계정이 곧 도구의 userId 가 된다.
                                        계정을 바꿔 가며 같은 질문을 던져 보는 것이 계좌 소유자 격리 실습이다.
                                        """)));
    }
}
