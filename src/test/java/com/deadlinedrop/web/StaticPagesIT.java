package com.deadlinedrop.web;

import com.deadlinedrop.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying the shared static frontend layer,
 * asset serving, and security policy rules (no inline scripts, no style attributes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StaticPagesIT {

    private final TestRestTemplate rest;

    @Autowired
    StaticPagesIT(TestRestTemplate rest) {
        this.rest = rest;
    }

    @Test
    void getRoot_returns200AndContainsLoginForm() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("id=\"login-form\"");
        assertThat(response.getBody()).contains("id=\"email\"", "id=\"password\"");
    }

    @Test
    void getTokensCss_returns200WithTextCss() {
        ResponseEntity<String> response = rest.getForEntity("/css/tokens.css", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/css");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains(":root");
    }

    @Test
    void getStudentAssignmentsPage_returns200() {
        ResponseEntity<String> response = rest.getForEntity("/student/assignments.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("My Assignments");
        assertThat(response.getBody()).contains("assignments-container");
    }

    @Test
    void getStudentSubmitPage_returns200() {
        ResponseEntity<String> response = rest.getForEntity("/student/submit.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("Submit Assignment");
        assertThat(response.getBody()).contains("file-input");
    }

    @Test
    void getTeacherSetupPages_return200() {
        ResponseEntity<String> courses = rest.getForEntity("/teacher/courses.html", String.class);
        assertThat(courses.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(courses.getBody()).contains("New course", "courses-container", "/js/courses.js");

        ResponseEntity<String> assignmentForm = rest.getForEntity("/teacher/assignment-form.html", String.class);
        assertThat(assignmentForm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assignmentForm.getBody()).contains("Students see:", "Maximum 25 MB", "/js/assignment-form.js");
    }

    @Test
    void getReceiptAndConsentPages_return200() {
        ResponseEntity<String> receipt = rest.getForEntity("/student/receipt.html", String.class);
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receipt.getBody()).contains("receipt-root");

        ResponseEntity<String> consent = rest.getForEntity("/consent-v1.html", String.class);
        assertThat(consent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consent.getBody()).contains("26 Nov 2026").contains("exactly the same without consent");
    }

    @Test
    void noStaticFileContainsInlineScriptsOrStyleAttributes() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:/static/**");
        assertThat(resources).isNotEmpty();

        Pattern scriptTagPattern = Pattern.compile("<script\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Pattern styleAttributePattern = Pattern.compile("\\bstyle\\s*=", Pattern.CASE_INSENSITIVE);

        int scannedHtmlFiles = 0;
        int totalScannedFiles = 0;

        for (Resource resource : resources) {
            if (!resource.isReadable() || resource.getFilename() == null) {
                continue;
            }
            String filename = resource.getFilename().toLowerCase();
            if (!filename.endsWith(".html") && !filename.endsWith(".js") && !filename.endsWith(".css")) {
                continue;
            }

            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            totalScannedFiles++;

            if (filename.endsWith(".html")) {
                scannedHtmlFiles++;

                // Enforce no style= attribute
                assertThat(styleAttributePattern.matcher(content).find())
                        .as("HTML file %s must not contain 'style=' attribute", resource.getFilename())
                        .isFalse();

                // Enforce no inline <script> without src
                Matcher scriptMatcher = scriptTagPattern.matcher(content);
                while (scriptMatcher.find()) {
                    String attributes = scriptMatcher.group(1);
                    boolean hasSrc = Pattern.compile("\\bsrc\\s*=", Pattern.CASE_INSENSITIVE).matcher(attributes).find();
                    assertThat(hasSrc)
                            .as("Script tag in %s must have a src attribute: %s", resource.getFilename(), scriptMatcher.group(0))
                            .isTrue();
                }
            }
        }

        assertThat(scannedHtmlFiles).isGreaterThanOrEqualTo(3);
        assertThat(totalScannedFiles).isGreaterThanOrEqualTo(7);
    }
}
