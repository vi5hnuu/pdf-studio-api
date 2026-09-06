package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.SizeUnit;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ToolCreditCost;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.ToolCreditCostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds default tool credit costs on startup — only for tool ids not already present, so
 * runtime edits (admin repricing) survive and newly added tools get a default. The table
 * is the single source of truth the app and the {@code @ChargeCredits} aspect read.
 *
 * <p>Default policy: common "organize" operations are free (0) to stay competitive; heavy
 * server work (compress, conversions, imposition, repair, image ops) costs credits. Tune
 * any row in the DB without a redeploy.
 */
@Component
@Order(1) // before ToolCostConsistencyChecker, which verifies what this seeds
@RequiredArgsConstructor
@Slf4j
public class ToolCreditCostSeeder implements ApplicationRunner {

    private static final long FIVE_MB = 5_000_000L;

    private final ToolCreditCostRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        List<ToolCreditCost> defaults = defaults();
        List<ToolCreditCost> toInsert = new ArrayList<>();
        for (ToolCreditCost c : defaults) {
            if (!repository.existsById(c.getToolId())) toInsert.add(c);
        }
        if (!toInsert.isEmpty()) {
            repository.saveAll(toInsert);
            log.info("Seeded {} tool credit cost rows.", toInsert.size());
        }
    }

    private List<ToolCreditCost> defaults() {
        List<ToolCreditCost> list = new ArrayList<>();

        // ── Free "organize" basics (competitive free tier) ──
        for (String free : List.of(
                "merge-pdf", "reorder-pdf", "split-pdf", "rotate-pdf",
                "get-metadata", "edit-metadata", "remove-metadata",
                "get-bookmarks", "edit-bookmarks", "analyze-pdf", "get-form-fields",
                "add-blank-pages", "duplicate-pages", "remove-blank-pages",
                "extract-text", "sanitize-pdf", "insert-pdf", "replace-pages",
                "rotate-image", "flip-image", "border-image", "resize-image")) {
            list.add(flat(free, 0));
        }

        // ── Heavy / premium PDF tools ──
        list.add(sized("compress-pdf", 2, SizeUnit.BYTES, 1, FIVE_MB)); // +1 credit / 5 MB
        list.add(flat("pdf-to-word", 3));
        list.add(flat("pdf-to-excel", 3));
        list.add(flat("pdf-to-pptx", 3));
        list.add(flat("pdf-to-jpg", 2));
        list.add(flat("image-to-pdf", 1));
        list.add(flat("n-up", 1));
        list.add(flat("repair-pdf", 2));
        list.add(flat("optimize-pdf", 2));
        list.add(flat("redact-pdf", 2));
        list.add(flat("watermark-pdf", 1));
        list.add(flat("stamp-pdf", 1));
        list.add(flat("grayscale-pdf", 1));
        list.add(flat("crop-pdf", 1));
        list.add(flat("flatten-pdf", 1));
        list.add(flat("fill-flatten", 1));
        list.add(flat("create-form", 1));
        list.add(flat("header-footer", 1));
        list.add(flat("page-numbers", 1));
        list.add(flat("place-image", 1));
        list.add(flat("extract-images", 1));
        list.add(flat("extract-fonts", 1));
        list.add(flat("extract-embedded-files", 1));
        list.add(flat("mirror-pdf", 1));
        list.add(flat("resize-page", 1));
        list.add(flat("scale-pdf", 1));
        list.add(flat("split-by-size", 1));
        list.add(flat("protect-pdf", 1));
        list.add(flat("unprotect-pdf", 1));

        // ── Image studio ──
        list.add(flat("compress-image", 1));
        list.add(flat("convert-to-jpg", 1));
        list.add(flat("convert-from-jpg", 1));
        list.add(flat("filter-image", 1));

        return list;
    }

    private ToolCreditCost flat(String toolId, int base) {
        return ToolCreditCost.builder().toolId(toolId).baseCredits(base)
                .sizeUnit(SizeUnit.NONE).creditsPerUnit(0).unitSize(1).active(true).build();
    }

    private ToolCreditCost sized(String toolId, int base, SizeUnit unit, int perUnit, long unitSize) {
        return ToolCreditCost.builder().toolId(toolId).baseCredits(base)
                .sizeUnit(unit).creditsPerUnit(perUnit).unitSize(unitSize).active(true).build();
    }
}
