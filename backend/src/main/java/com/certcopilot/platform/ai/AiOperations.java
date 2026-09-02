package com.certcopilot.platform.ai;

import com.certcopilot.platform.ai.validators.LearningPackValidator;
import com.certcopilot.platform.ai.validators.QuizValidator;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The complete AI surface of the product: six operations, declared in one place.
 *
 * <p>Anything not on this list cannot reach a model, because {@link AiGateway} is
 * the only caller of {@link LlmPort} and it requires a registered operation.
 * Adding a feature means adding a declaration, a prompt file and a validator -
 * never new retry, cost or cache code.
 *
 * <p>Tier assignment follows one rule: quality-critical and learner-facing goes
 * to QUALITY, internal signals go to FAST. That is the main cost lever.
 */
@Component
public class AiOperations {

    public static final String STRUCTURE_EXTRACT = "material.structure.extract";
    public static final String TOPICS_EXTRACT = "material.topics.extract";
    public static final String TOPIC_TO_TASK = "mapping.topic.to.task";
    public static final String PACK_GENERATE = "learning.pack.generate";
    public static final String QUIZ_GENERATE = "assessment.quiz.generate";
    public static final String WEAKNESS_EXPLAIN = "assessment.weakness.explain";

    /** Bumped when the model or its sampling configuration changes (part of the artifact key). */
    private static final String MODEL_CONFIG_VERSION = "cfg-2026-08";

    private final AiOperationRegistry registry;
    private final DomainValidator structureValidator;
    private final DomainValidator topicsValidator;
    private final DomainValidator mappingValidator;
    private final DomainValidator weaknessValidator;
    private final LearningPackValidator packValidator;
    private final QuizValidator quizValidator;

    public AiOperations(AiOperationRegistry registry,
                        @Qualifier("structureValidator") DomainValidator structureValidator,
                        @Qualifier("topicsValidator") DomainValidator topicsValidator,
                        @Qualifier("mappingValidator") DomainValidator mappingValidator,
                        @Qualifier("weaknessValidator") DomainValidator weaknessValidator,
                        LearningPackValidator packValidator,
                        QuizValidator quizValidator) {
        this.registry = registry;
        this.structureValidator = structureValidator;
        this.topicsValidator = topicsValidator;
        this.mappingValidator = mappingValidator;
        this.weaknessValidator = weaknessValidator;
        this.packValidator = packValidator;
        this.quizValidator = quizValidator;
    }

    @PostConstruct
    void registerAll() {
        registry.register(new AiOperation(
                STRUCTURE_EXTRACT, "v1", "v1", MODEL_CONFIG_VERSION,
                ModelTier.FAST, 4000, 3, 3, structureValidator, true));

        registry.register(new AiOperation(
                TOPICS_EXTRACT, "v1", "v1", MODEL_CONFIG_VERSION,
                ModelTier.FAST, 4000, 3, 3, topicsValidator, true));

        registry.register(new AiOperation(
                TOPIC_TO_TASK, "v1", "v1", MODEL_CONFIG_VERSION,
                ModelTier.QUALITY, 6000, 15, 3, mappingValidator, true));

        // v2 spells out that `type` and `origin` are different fields, and that a
        // block with no page range must be AI_SUPPLEMENT. A real model read v1 as
        // permission to put "AI_SUPPLEMENT" in `type`, and to mark exam tips as
        // material-origin with no page - both rejected, three times, at full
        // price. The version bump is not cosmetic: prompt version is part of the
        // artifact key, so without it the old prompt's output would keep being
        // served from cache.
        registry.register(new AiOperation(
                PACK_GENERATE, "v2", "v1", MODEL_CONFIG_VERSION,
                ModelTier.QUALITY, 5000, 18, 3, packValidator, true));

        registry.register(new AiOperation(
                QUIZ_GENERATE, "v1", "v1", MODEL_CONFIG_VERSION,
                ModelTier.QUALITY, 5000, 12, 3, quizValidator, true));

        // Not essential: at the hard budget cap this is refused first, because a
        // missing explanation is an inconvenience while a missing lesson is not.
        registry.register(new AiOperation(
                WEAKNESS_EXPLAIN, "v1", "v1", MODEL_CONFIG_VERSION,
                ModelTier.FAST, 1200, 2, 2, weaknessValidator, false));
    }
}
