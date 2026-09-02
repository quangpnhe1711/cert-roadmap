package com.certcopilot.support;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes the synthetic deck to disk so a manual HTTP walkthrough has a real file
 * to upload. Disabled unless explicitly requested, so it never runs in CI.
 *
 * <pre>./mvnw test -Dtest=DumpDeckTest -Ddump.deck=target/aif-c01-course.pdf</pre>
 */
class DumpDeckTest {

    @Test
    @EnabledIfSystemProperty(named = "dump.deck", matches = ".+")
    void writeDeck() throws Exception {
        Path target = Path.of(System.getProperty("dump.deck")).toAbsolutePath();
        Files.createDirectories(target.getParent());
        byte[] pdf = SyntheticDeck.build(2);
        Files.write(target, pdf);
        System.out.println("wrote " + pdf.length + " bytes to " + target);
    }
}
