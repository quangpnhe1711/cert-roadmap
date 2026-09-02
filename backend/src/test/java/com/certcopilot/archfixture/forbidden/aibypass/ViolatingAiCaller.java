package com.certcopilot.archfixture.forbidden.aibypass;

import com.certcopilot.platform.ai.LlmPort;
import com.certcopilot.platform.ai.ModelTier;

/**
 * Correction P3 fixture: a feature that calls the model directly.
 *
 * <p>This is the bypass that matters. It skips the artifact cache, the budget
 * reservation, the cost ledger and every validator in one line, and it would
 * look perfectly reasonable in review. Rule R5 must DETECT this class.
 */
public class ViolatingAiCaller {

    private final LlmPort llm;

    public ViolatingAiCaller(LlmPort llm) {
        this.llm = llm;
    }

    public String ask(String prompt) {
        return llm.call(new LlmPort.Request(
                "ad.hoc", ModelTier.FAST, prompt, "v1", 500, 1, null, null)).rawOutput();
    }
}
