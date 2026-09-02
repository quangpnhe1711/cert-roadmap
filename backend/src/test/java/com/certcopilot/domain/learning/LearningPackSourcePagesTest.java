package com.certcopilot.domain.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the page range off one generated block.
 *
 * <p>Worth its own test because the interesting case is the one the deterministic
 * fake never produces. The fake always emits both page fields, so a block with
 * neither - which is exactly what a supplement block from a real model looks like
 * - was first seen in production output, where it threw a
 * {@link NullPointerException} and discarded a lesson that had already been paid
 * for. Offline coverage that only exercises fixture-shaped output is coverage of
 * the fixture.
 */
class LearningPackSourcePagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("a block with no page range at all yields nulls rather than throwing")
    void aBlockWithNoPageRangeIsNotAnError() {
        LearningPackService.SourcePages pages = read("""
                {"type":"concept","origin":"AI_SUPPLEMENT","payload":{"text":"background"}}""");

        assertThat(pages.start()).isNull();
        assertThat(pages.end()).isNull();
    }

    @Test
    @DisplayName("a start with no end covers the single page it names")
    void anEndDefaultsToTheStart() {
        LearningPackService.SourcePages pages = read("""
                {"type":"concept","sourcePageStart":7}""");

        assertThat(pages.start()).isEqualTo(7);
        assertThat(pages.end()).isEqualTo(7);
    }

    @Test
    @DisplayName("an explicit range is kept")
    void anExplicitRangeIsKept() {
        LearningPackService.SourcePages pages = read("""
                {"type":"concept","sourcePageStart":3,"sourcePageEnd":9}""");

        assertThat(pages.start()).isEqualTo(3);
        assertThat(pages.end()).isEqualTo(9);
    }

    @Test
    @DisplayName("an explicit JSON null is treated as absent, not as zero")
    void jsonNullIsAbsent() {
        LearningPackService.SourcePages pages = read("""
                {"type":"concept","sourcePageStart":null,"sourcePageEnd":null}""");

        assertThat(pages.start()).isNull();
        assertThat(pages.end()).isNull();
    }

    private LearningPackService.SourcePages read(String json) {
        try {
            return LearningPackService.sourcePages(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
