package com.certcopilot.platform.parsing;

import java.io.InputStream;

/**
 * Format-specific extraction. Implementations must preserve page identity,
 * reading order and whatever hierarchy the format actually carries, and must
 * report honestly when a page is mostly visual.
 */
public interface DocumentParserPort {

    boolean supports(String mimeType, String fileName);

    ParsedDocument parse(InputStream input, String fileName);

    /** Extraction algorithm version; part of the material revision identity. */
    String extractionVersion();
}
