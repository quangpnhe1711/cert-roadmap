package com.certcopilot.domain.learning;

import java.util.UUID;

import com.certcopilot.domain.assessment.QuizService;
import com.certcopilot.platform.jobs.JobHandler;
import com.certcopilot.platform.jobs.JobRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generates a unit's lesson and its questions together.
 *
 * <p>Kept as one job because the two share the same source text and the learner
 * needs both before the day opens: arriving at a finished lesson with no quiz is
 * a broken feedback loop.
 *
 * <p>Idempotent through the artifact key, so a redelivered job costs nothing.
 */
@Component
public class PackGenerationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PackGenerationHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String JOB_TYPE = "GENERATE_PACK";

    private final LearningPackService packs;
    private final QuizService quizzes;

    public PackGenerationHandler(LearningPackService packs, QuizService quizzes) {
        this.packs = packs;
        this.quizzes = quizzes;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(JobRecord job) throws Exception {
        JsonNode payload = MAPPER.readTree(job.payload());
        UUID planId = UUID.fromString(payload.path("planId").asText());
        UUID unitId = UUID.fromString(payload.path("learningUnitId").asText());

        packs.generate(planId, unitId).ifPresentOrElse(
                pack -> log.debug("pack ready for unit {} ({} blocks)", unitId, pack.blocks().size()),
                () -> log.warn("no pack produced for unit {}", unitId));

        // A failure here must not lose the lesson that already succeeded.
        try {
            int questions = quizzes.generateForUnit(planId, unitId);
            log.debug("unit {}: {} questions banked", unitId, questions);
        } catch (RuntimeException e) {
            log.warn("quiz generation failed for unit {} but the lesson is available: {}",
                    unitId, e.toString());
        }
    }
}
