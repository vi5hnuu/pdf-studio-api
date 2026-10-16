package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.ChargeCredits;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.ToolCreditCostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup if any {@link ChargeCredits} endpoint has no row in {@code tool_credit_costs}.
 *
 * <p>Pricing is resolved by string id, so a typo in {@code @ChargeCredits(tool = "…")} or a row
 * deleted during a repricing makes a premium tool permanently free — with no error, no failed
 * request, and nothing to notice until someone reconciles revenue. Turning that into a startup
 * failure means the mismatch surfaces at deploy time, when it is still cheap to fix.
 *
 * <p>Runs after {@code ToolCreditCostSeeder} so newly added tools are seeded before being checked.
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class ToolCostConsistencyChecker implements ApplicationRunner {

    private final ApplicationContext applicationContext;
    private final ToolCreditCostRepository costRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        List<String> freeButCharged = new ArrayList<>();
        int checked = 0;

        for (Object controller : applicationContext.getBeansWithAnnotation(RestController.class).values()) {
            // Unwrap any proxy so the annotations on the real methods are visible.
            for (Method method : AopUtils.getTargetClass(controller).getDeclaredMethods()) {
                ChargeCredits annotation = method.getAnnotation(ChargeCredits.class);
                if (annotation == null) continue;
                checked++;
                costRepository.findById(annotation.tool()).ifPresentOrElse(cost -> {
                    // A row at zero passes the existence check but still runs free, which is how
                    // a newly priced tool can ship charging nothing: the seeder only inserts
                    // rows it does not find, so an environment that already had the tool free
                    // keeps that row and the new default never applies.
                    if (cost.getBaseCredits() <= 0 && cost.getCreditsPerUnit() <= 0) {
                        freeButCharged.add(annotation.tool() + " (" + method.getName() + ")");
                    }
                }, () -> missing.add(annotation.tool() + " (" + method.getName() + ")"));
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "These @ChargeCredits tools have no tool_credit_costs row and would run FREE: "
                            + String.join(", ", missing)
                            + ". Add the rows (or a default in ToolCreditCostSeeder) before starting.");
        }
        // Warned rather than fatal: zero may be deliberate — a promotion, or a tool being made
        // free for a while — and taking the service down over a pricing choice would be worse
        // than saying so loudly.
        if (!freeButCharged.isEmpty()) {
            log.warn("These tools are annotated @ChargeCredits but priced at 0, so they run FREE: {}."
                            + " Reprice via PUT /api/v1/admin/tool-costs/{} if that is not intended.",
                    String.join(", ", freeButCharged), "{toolId}");
        }
        log.info("Verified credit pricing for {} charged tool endpoints.", checked);
    }
}
