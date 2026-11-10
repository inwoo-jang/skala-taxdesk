package com.inwoo.taxdesk.web;

import com.inwoo.taxdesk.chat.ConversationState;
import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.tools.AccountIncomeTools;
import com.inwoo.taxdesk.tools.ToolUser;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "내 금융소득을 한눈에" — 모델을 거치지 않는 현황 조회.
 *
 * <p>{@code userId} 는 요청에서 받지 않고 {@link Principal} 에서 꺼낸다. {@code /chat} 과 같은 규칙이다.
 * 대화에서 말한 타사 금액은 {@code conversationId} 를 주면 함께 보여준다 — 그것도 소유자로 범위가 묶인다.
 */
@RestController
@RequestMapping("/income")
public class IncomeController {

    private static final Logger log = LoggerFactory.getLogger(IncomeController.class);

    private final AccountIncomeTools incomeTools;
    private final ConversationState state;
    private final TaxDeskProperties props;

    public IncomeController(AccountIncomeTools incomeTools, ConversationState state, TaxDeskProperties props) {
        this.incomeTools = incomeTools;
        this.state = state;
        this.props = props;
    }

    @GetMapping("/overview")
    public AccountIncomeTools.IncomeOverview overview(@RequestParam(required = false) Integer year,
                                                      @RequestParam(required = false) String conversationId,
                                                      Principal principal) {
        int y = year == null ? props.taxYear() : year;
        Long external = conversationId == null || conversationId.isBlank() ? null
                : state.get(principal.getName() + ":" + conversationId).externalAmount();
        try {
            return incomeTools.overview(y, new ToolContext(ToolUser.contextOf(principal.getName())), external);
        } catch (DataAccessException e) {
            // 조회 실패를 500 으로 끝내면 화면은 "0원"과 구별할 방법이 없다. 상태로 돌려준다.
            log.error("금융소득 현황 조회 실패: {}", e.toString());
            return AccountIncomeTools.failed(y, external);
        }
    }
}
