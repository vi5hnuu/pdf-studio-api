package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.ToolCostUpdateRequest;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ToolCreditCost;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.ToolCreditCostRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Repricing tools at runtime.
 *
 * <p>{@code ToolCreditCostSeeder} describes the cost table as editable without a redeploy,
 * but nothing exposed it — the only way to change a price was raw SQL against the
 * production database. Every change is logged with the admin who made it, because this
 * endpoint moves money.
 *
 * <p>Guarded by {@code ROLE_ADMIN}, which the auth service puts in the token's roles claim.
 */
@RestController
@RequestMapping("/api/v1/admin/tool-costs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class AdminCreditsController {

    private final ToolCreditCostRepository costRepository;

    /** Every row, including inactive ones — unlike the public list, which hides them. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<ToolCreditCost> all = costRepository.findAll();
        return ResponseEntity.ok(Map.of("success", true, "data", all));
    }

    /**
     * Reprices one tool. The tool must already exist: creating a row here would let a typo
     * introduce a price for an endpoint that does not exist, which nothing would ever read.
     */
    @PutMapping("/{toolId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String toolId,
                                                      @Valid @RequestBody ToolCostUpdateRequest request) {
        ToolCreditCost cost = costRepository.findById(toolId)
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "TOOL_NOT_FOUND",
                        "No such tool: " + toolId));

        final int previousBase = cost.getBaseCredits();
        cost.setBaseCredits(request.baseCredits());
        cost.setSizeUnit(request.sizeUnit());
        cost.setCreditsPerUnit(request.creditsPerUnit());
        cost.setUnitSize(request.unitSize());
        cost.setActive(request.active());
        costRepository.save(cost);

        log.info("Tool repriced by {}: tool={} base {} -> {} (unit={} perUnit={} size={} active={})",
                CurrentUser.id(), toolId, previousBase, cost.getBaseCredits(),
                cost.getSizeUnit(), cost.getCreditsPerUnit(), cost.getUnitSize(), cost.isActive());

        return ResponseEntity.ok(Map.of("success", true, "message", "Price updated.", "data", cost));
    }
}
