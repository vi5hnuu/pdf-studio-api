package com.vishnu.pdf_studio_api.pdfstudioapi.web;

import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CreditsEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired CreditsService creditsService;

    private RequestPostProcessor as(String principalKey, String... roles) {
        List<String> roleList = roles.length == 0 ? List.of("ROLE_USER") : List.of(roles);
        return jwt().jwt(b -> b.subject(principalKey).claim("roles", roleList))
                .authorities(roleList.stream()
                        .map(role -> (org.springframework.security.core.GrantedAuthority)
                                new SimpleGrantedAuthority(role))
                        .toList());
    }

    @Test
    @DisplayName("the ledger returns only the caller's own history")
    void ledgerIsScopedToCaller() throws Exception {
        String mine = "app:" + UUID.randomUUID();
        String theirs = "app:" + UUID.randomUUID();

        creditsService.charge(mine, "grayscale-pdf", 0L, "mine-" + mine, null);
        creditsService.charge(theirs, "compress-pdf", 0L, "theirs-" + theirs, null);

        mockMvc.perform(get("/api/v1/credits/ledger").with(as(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // The welcome grant plus the one debit, and nothing belonging to the other user.
                .andExpect(jsonPath("$.data.entries[?(@.toolId == 'grayscale-pdf')]").exists())
                .andExpect(jsonPath("$.data.entries[?(@.toolId == 'compress-pdf')]").doesNotExist());
    }

    @Test
    @DisplayName("ledger rows carry the signed delta and the balance after")
    void ledgerRowsAreUsable() throws Exception {
        String user = "app:" + UUID.randomUUID();
        creditsService.charge(user, "grayscale-pdf", 0L, "k-" + user, null);

        mockMvc.perform(get("/api/v1/credits/ledger?page=0&size=5").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.entries[0].delta").value(-1))
                .andExpect(jsonPath("$.data.entries[0].reason").value("DEBIT"))
                .andExpect(jsonPath("$.data.entries[1].reason").value("WELCOME"));
    }

    @Test
    @DisplayName("the page size is clamped so a client cannot request the whole table")
    void clampsPageSize() throws Exception {
        String user = "app:" + UUID.randomUUID();
        creditsService.getBalance(user, null);

        mockMvc.perform(get("/api/v1/credits/ledger?size=100000").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("repricing is refused to a non-admin")
    void repricingRequiresAdmin() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tool-costs/grayscale-pdf")
                        .contentType("application/json")
                        .content("""
                                {"baseCredits":5,"sizeUnit":"NONE","creditsPerUnit":0,"unitSize":1,"active":true}""")
                        .with(as("app:" + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can reprice a tool and the new price takes effect immediately")
    void adminCanReprice() throws Exception {
        int before = creditsService.resolveCost("crop-pdf", 0L);

        mockMvc.perform(put("/api/v1/admin/tool-costs/crop-pdf")
                        .contentType("application/json")
                        .content("""
                                {"baseCredits":7,"sizeUnit":"NONE","creditsPerUnit":0,"unitSize":1,"active":true}""")
                        .with(as("app:admin-" + UUID.randomUUID(), "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseCredits").value(7));

        // The charging path reads the table, so the change is live without a redeploy.
        org.junit.jupiter.api.Assertions.assertEquals(7, creditsService.resolveCost("crop-pdf", 0L));
        org.junit.jupiter.api.Assertions.assertNotEquals(before, 7);
    }

    @Test
    @DisplayName("repricing an unknown tool is a 404, not a silently created row")
    void repricingUnknownToolFails() throws Exception {
        mockMvc.perform(put("/api/v1/admin/tool-costs/not-a-real-tool")
                        .contentType("application/json")
                        .content("""
                                {"baseCredits":1,"sizeUnit":"NONE","creditsPerUnit":0,"unitSize":1,"active":true}""")
                        .with(as("app:admin-" + UUID.randomUUID(), "ROLE_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOOL_NOT_FOUND"));
    }
}
