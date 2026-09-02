package com.certcopilot.archfixture.allowed.assessment.internal.grading;

import java.util.Set;

/**
 * Correction P3 fixture: deterministic grading, no AI dependency.
 * Rule R2 must PASS against this class.
 */
public class CompliantGrader {

    public boolean isCorrect(Set<String> selected, Set<String> expected) {
        return selected.equals(expected);
    }
}
