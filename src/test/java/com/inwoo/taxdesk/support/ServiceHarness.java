package com.inwoo.taxdesk.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inwoo.taxdesk.advisor.UsageRegistry;
import com.inwoo.taxdesk.chat.ConversationState;
import com.inwoo.taxdesk.chat.QueryRouter;
import com.inwoo.taxdesk.chat.TaxDeskService;
import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.domain.AccountIncome;
import com.inwoo.taxdesk.domain.IncomeSource;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.ProductApplication;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.rag.DocumentMeta;
import com.inwoo.taxdesk.repository.AccountIncomeRepository;
import com.inwoo.taxdesk.repository.ProductApplicationRepository;
import com.inwoo.taxdesk.tools.AccountIncomeTools;
import com.inwoo.taxdesk.tools.ProductApplicationTools;
import com.inwoo.taxdesk.tools.TaxCalcTools;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;

/**
 * {@link TaxDeskService} 를 <b>모델·API 키·네트워크 없이</b> 조립한다.
 *
 * <p>모델은 {@link StubChatModel}, 벡터 스토어는 {@link FakeVectorStore}, 저장소는 Mockito 다.
 * 서비스 테스트들이 공유한다.
 */
public class ServiceHarness {

    public static final String USER = "antman";
    public static final String CONV = "antman:t";

    public final StubChatModel main = new StubChatModel("모델 답변입니다.");
    public final StubChatModel routerModel = new StubChatModel("HYBRID");
    public final FakeVectorStore store = new FakeVectorStore();
    public final AccountIncomeRepository incomeRepo = mock(AccountIncomeRepository.class);
    public final ProductApplicationRepository appRepo = mock(ProductApplicationRepository.class);
    public final ConversationState state = new ConversationState();
    public final List<ProductApplication> savedApplications = new ArrayList<>();
    public final TaxDeskProperties props;
    public final TaxDeskService service;

    public ServiceHarness() {
        this(TestProps.props2026());
    }

    public ServiceHarness(TaxDeskProperties props) {
        this.props = props;
        when(appRepo.save(any())).thenAnswer(inv -> {
            ProductApplication a = inv.getArgument(0);
            savedApplications.add(a);
            return a;
        });
        when(appRepo.findByOwnerIdOrderByRequestedAtDesc(anyString())).thenAnswer(inv ->
                savedApplications.stream().filter(a -> a.getOwnerId().equals(inv.getArgument(0))).toList());
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        this.service = new TaxDeskService(
                ChatClient.builder(main).build(),
                ChatClient.builder(new StubChatModel("폴백")).build(),
                new QueryRouter(ChatClient.builder(routerModel).build()),
                new AccountIncomeTools(incomeRepo),
                new TaxCalcTools(props),
                store,
                memory,
                new UsageRegistry(),
                state,
                new ProductApplicationTools(appRepo),
                props);
    }

    /** antman 시드 — 분리과세 종결 (15,259,710). 합성 데이터다. */
    public void seedAntman() {
        when(incomeRepo.findByOwnerIdAndYear(USER, 2026)).thenReturn(List.of(
                row("a1", "국민은행", IncomeSource.LINKED, "정기예금 이자", IncomeType.INTEREST, 8_347_210L, 1_168_600L, TaxTreatment.TAXABLE),
                row("a2", "SKALA증권", IncomeSource.OWN_ACCOUNT, "국내주식 배당", IncomeType.DOMESTIC_DIVIDEND, 6_912_500L, 967_750L, TaxTreatment.TAXABLE)));
    }

    /** isabu 시드 — 종합과세 (21,528,440) + ISA 제외분. 합성 데이터다. */
    public void seedIsabu() {
        when(incomeRepo.findByOwnerIdAndYear(USER, 2026)).thenReturn(List.of(
                row("i1", "하나은행", IncomeSource.LINKED, "적금 이자", IncomeType.INTEREST, 9_128_440L, 1_277_980L, TaxTreatment.TAXABLE),
                row("i2", "SKALA증권", IncomeSource.OWN_ACCOUNT, "국내주식 배당", IncomeType.DOMESTIC_DIVIDEND, 12_400_000L, 1_736_000L, TaxTreatment.TAXABLE),
                row("i3", "SKALA증권", IncomeSource.OWN_ACCOUNT, "ISA 비과세 한도분", IncomeType.INTEREST, 4_000_000L, 0L, TaxTreatment.TAX_FREE),
                row("i4", "SKALA증권", IncomeSource.OWN_ACCOUNT, "ISA 한도 초과분(9% 분리과세)", IncomeType.INTEREST, 5_120_000L, 460_800L, TaxTreatment.SEPARATE)));
    }

    public void seedLawDoc(String article, String text) {
        var meta = new DocumentMeta("01.md", "금융소득 종합과세 기준", article, 2026, "LAW",
                "소득세법 법률 제21221호 (시행 2026-07-01)", "law.go.kr", "2026-08-20");
        store.add(List.of(new Document("d-" + article, text, meta.toMap())));
    }

    private static AccountIncome row(String id, String inst, IncomeSource src, String product, IncomeType type,
                                     long amount, long withheld, TaxTreatment treatment) {
        return new AccountIncome(id, USER, "110123456789", 2026, inst, src, product, type, amount, withheld, treatment);
    }
}
