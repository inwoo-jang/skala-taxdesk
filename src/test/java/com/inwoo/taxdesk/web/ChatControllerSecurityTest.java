package com.inwoo.taxdesk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inwoo.taxdesk.chat.AnswerDto;
import com.inwoo.taxdesk.chat.ConversationState;
import com.inwoo.taxdesk.chat.Route;
import com.inwoo.taxdesk.chat.TaxDeskService;
import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.tools.AccountIncomeTools;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 계정 경계 — HTTP 계층. <b>모델·DB 없이 돈다.</b>
 *
 * <ul>
 *   <li>인증 없는 /chat 은 401</li>
 *   <li>admin 은 /chat 을 쓸 수 없다 (403) — 관리자 세션으로 남의 계좌를 묻는 경로 자체가 없다</li>
 *   <li>고객이 보낸 conversationId 는 계정으로 범위가 묶인다 — 본문에 userId 를 넣어도 무시된다</li>
 *   <li>/income/overview 도 같은 규칙이다</li>
 * </ul>
 */
@WebMvcTest(controllers = {ChatController.class, IncomeController.class})
@Import(SecurityConfig.class)
@EnableConfigurationProperties(TaxDeskProperties.class)
@TestPropertySource(properties = {
        "taxdesk.users.antman=pass1", "taxdesk.users.richman=pass4", "taxdesk.admins.admin=admin",
        "taxdesk.tax-year=2026", "taxdesk.rule-version=v", "taxdesk.rule-verified-on=2026-08-20"})
class ChatControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TaxDeskService service;

    @MockitoBean
    AccountIncomeTools incomeTools;

    @MockitoBean
    ConversationState state;

    @Autowired
    TaxDeskProperties props;

    private static AnswerDto answer() {
        return new AnswerDto(Route.HYBRID, "ok", List.of(), List.of(), null, null, List.of(), null,
                new AnswerDto.Tokens(0, 0), 0, "d", "v");
    }

    @Test
    @DisplayName("인증 없이는 401")
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"저 종합과세 대상인가요?\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin 은 /chat 에 들어올 수 없다 — 403")
    void adminCannotChat() throws Exception {
        mvc.perform(post("/chat").with(httpBasic("admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"richman 배당 알려줘\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).ask(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("대화방은 로그인 계정으로 묶인다 — 본문의 userId 나 남의 방 이름은 소용없다")
    void conversationIsScopedToPrincipal() throws Exception {
        when(service.ask(anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(answer());

        mvc.perform(post("/chat").with(httpBasic("antman", "pass1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"저 종합과세 대상인가요?\",\"conversationId\":\"richman:demo\",\"userId\":\"richman\"}"))
                .andExpect(status().isOk());

        verify(service).ask(eq("antman"), eq("antman:richman:demo"), eq("저 종합과세 대상인가요?"), any(), any(), any());
    }

    @Test
    @DisplayName("history 도 같은 범위 규칙을 지난다")
    void historyIsScoped() throws Exception {
        when(service.history("antman:demo")).thenReturn(List.of());
        mvc.perform(get("/chat/history").param("conversationId", "demo").with(httpBasic("antman", "pass1")))
                .andExpect(status().isOk());
        verify(service).history("antman:demo");
    }

    @Test
    @DisplayName("/income/overview 는 로그인 계정의 것만 — admin 은 403")
    void overviewIsScoped() throws Exception {
        when(state.get(anyString())).thenReturn(ConversationState.Facts.empty());
        when(incomeTools.overview(eq(2026), any(), any())).thenReturn(
                new AccountIncomeTools.IncomeOverview(2026, java.time.LocalDate.now(), List.of(),
                        java.util.Map.of(), java.util.Map.of(), null, "UNKNOWN", List.of(), List.of()));

        mvc.perform(get("/income/overview").with(httpBasic("antman", "pass1"))).andExpect(status().isOk());
        mvc.perform(get("/income/overview").with(httpBasic("admin", "admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/income/overview")).andExpect(status().isUnauthorized());

        assertThat(props.taxYear()).isEqualTo(2026);
    }
}
