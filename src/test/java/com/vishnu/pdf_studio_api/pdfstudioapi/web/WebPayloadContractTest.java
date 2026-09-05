package com.vishnu.pdf_studio_api.pdfstudioapi.web;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Posts the exact JSON the web tool pages send, to each endpoint they target.
 *
 * <p>The web tools are generated from a declaration, so a field named wrongly there would
 * be silently ignored by Jackson and the tool would quietly run with defaults instead of
 * the user's settings — no error, just the wrong output. These pin the field names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebPayloadContractTest {

    @Autowired MockMvc mockMvc;

    private byte[] pdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private RequestPostProcessor user() {
        return jwt().jwt(b -> b.subject("app:" + UUID.randomUUID()).claim("roles", List.of("ROLE_USER")))
                .authorities((org.springframework.security.core.GrantedAuthority)
                        new SimpleGrantedAuthority("ROLE_USER"));
    }

    private MockMultipartFile part(String name, String json) {
        return new MockMultipartFile(name, "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile file(String field, byte[] content) throws Exception {
        return new MockMultipartFile(field, "a.pdf", "application/pdf", content);
    }

    /** Runs one endpoint with the web's payload and asserts it produced a file. */
    private void assertAccepts(String path, String infoPart, String json, MockMultipartFile... extra)
            throws Exception {
        var request = multipart("/api/v1/pdf-studio/" + path).file(file("file", pdf(4)));
        if (infoPart != null) request = request.file(part(infoPart, json));
        for (MockMultipartFile f : extra) request = request.file(f);

        MvcResult result = mockMvc.perform(request.with(user()).with(fromUniqueIp())).andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                path + " rejected the web payload: " + result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsByteArray().length > 0, path + " returned nothing");
    }

    /**
     * Every mock request otherwise arrives from 127.0.0.1, and welcome credits are rationed
     * per IP per day — so past the fifth test user the account opens with a zero balance and
     * the tool returns 402. Giving each user its own address keeps the guard's real behaviour
     * intact (it is covered by IpGrantGuardTest) without it capping the suite.
     */
    private static final java.util.concurrent.atomic.AtomicInteger _uniqueIpCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    private static RequestPostProcessor fromUniqueIp() {
        int n = _uniqueIpCounter.incrementAndGet();
        return request -> {
            request.setRemoteAddr("198.51.100." + (n % 250));
            return request;
        };
    }

    @Test
    @DisplayName("mirror-pdf accepts the direction the web sends")
    void mirror() throws Exception {
        assertAccepts("mirror-pdf", "mirror-pdf-info", "{\"direction\":\"VERTICAL\"}");
    }

    @Test
    @DisplayName("resize-page accepts the size preset the web sends")
    void resizePage() throws Exception {
        assertAccepts("resize-page", "resize-page-info", "{\"size\":\"LETTER\"}");
    }

    @Test
    @DisplayName("scale-pdf accepts the scale factor the web sends")
    void scale() throws Exception {
        assertAccepts("scale-pdf", "scale-pdf-info", "{\"scale\":0.5}");
    }

    @Test
    @DisplayName("split-by-size accepts max_size_mb and out_file_name")
    void splitBySize() throws Exception {
        assertAccepts("split-by-size", "split-by-size-info",
                "{\"max_size_mb\":1,\"out_file_name\":\"parts\"}");
    }

    @Test
    @DisplayName("insert-pdf accepts after_page and the second document")
    void insertPdf() throws Exception {
        assertAccepts("insert-pdf", "insert-pdf-info",
                "{\"after_page\":1,\"out_file_name\":\"merged\"}",
                new MockMultipartFile("insert", "b.pdf", "application/pdf", pdf(2)));
    }

    @Test
    @DisplayName("replace-pages accepts from, to and the replacement document")
    void replacePages() throws Exception {
        assertAccepts("replace-pages", "replace-pages-info",
                "{\"from\":1,\"to\":2,\"out_file_name\":\"replaced\"}",
                new MockMultipartFile("replacement", "b.pdf", "application/pdf", pdf(2)));
    }

    @Test
    @DisplayName("tools that take no options work with only the file part")
    void noOptionTools() throws Exception {
        assertAccepts("sanitize-pdf", null, null);
        assertAccepts("remove-metadata", null, null);
    }

    @Test
    @DisplayName("page-numbers works with the info part omitted entirely")
    void pageNumbersWithoutOptions() throws Exception {
        // The part is declared optional, and omitting it used to NullPointerException.
        assertAccepts("page-numbers", null, null);
    }

    @Test
    @DisplayName("image tools accept the fields the web sends")
    void imageTools() throws Exception {
        byte[] png = pngPixel();

        for (String[] tool : new String[][]{
                {"rotate-image", "rotate-image-info", "{\"angle\":180,\"out_file_name\":\"r\"}"},
                {"flip-image", "flip-image-info", "{\"direction\":\"VERTICAL\",\"out_file_name\":\"f\"}"},
                {"border-image", "border-image-info", "{\"width\":10,\"r\":255,\"g\":0,\"b\":0,\"out_file_name\":\"b\"}"},
        }) {
            MvcResult result = mockMvc.perform(multipart("/api/v1/image-studio/" + tool[0])
                    .file(new MockMultipartFile("file", "a.png", "image/png", png))
                    .file(part(tool[1], tool[2]))
                    .with(user()).with(fromUniqueIp())).andReturn();
            assertEquals(200, result.getResponse().getStatus(),
                    tool[0] + " rejected the web payload: " + result.getResponse().getContentAsString());
        }
    }

    /** Smallest valid PNG, so the image endpoints have something real to decode. */
    private byte[] pngPixel() throws Exception {
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        }
    }
}
