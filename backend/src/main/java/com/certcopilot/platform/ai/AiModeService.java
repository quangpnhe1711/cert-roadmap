package com.certcopilot.platform.ai;

import java.util.Locale;

import com.certcopilot.platform.ai.provider.GeminiProperties;
import com.certcopilot.platform.ai.provider.ProviderProperties;
import org.springframework.stereotype.Service;

/**
 * What the client is allowed to know about this deployment's AI configuration.
 *
 * <p>Exists so the answer comes from the server rather than from a build-time
 * flag baked into the bundle. A frontend that guesses its own provider from its
 * own environment is guessing about someone else's process, and the first time
 * it guesses wrong it tells a learner that fixture prose is a real lesson.
 *
 * <p>The API key is never a field here, never a getter, and never reaches
 * {@link Descriptor}. What is exposed is the provider name, whether a credential
 * is configured at all, and the model ids in use - enough to render an honest
 * status line, and nothing that would help anyone spend the account's money.
 */
@Service
public class AiModeService {

    /** Model id reported for the deterministic fake, which has no provider models. */
    private static final String NO_MODEL = "-";

    private final AiProperties aiProps;
    private final ProviderProperties anthropic;
    private final GeminiProperties gemini;

    public AiModeService(AiProperties aiProps, ProviderProperties anthropic, GeminiProperties gemini) {
        this.aiProps = aiProps;
        this.anthropic = anthropic;
        this.gemini = gemini;
    }

    public Descriptor describe() {
        String adapter = aiProps.getAdapter() == null
                ? "" : aiProps.getAdapter().trim().toLowerCase(Locale.ROOT);

        return switch (adapter) {
            case "gemini" -> new Descriptor(
                    "GEMINI", "Gemini", gemini.hasApiKey(), "MODEL", false,
                    gemini.getModels().get(ModelTier.FAST),
                    gemini.getModels().get(ModelTier.QUALITY));

            case "anthropic" -> new Descriptor(
                    "ANTHROPIC", "Anthropic", anthropic.hasApiKey(), "MODEL", false,
                    anthropic.getModels().get(ModelTier.FAST),
                    anthropic.getModels().get(ModelTier.QUALITY));

            // Anything unrecognised is treated as synthetic rather than as a real
            // provider. Guessing the other way is how a typo in one environment
            // variable removes the banner that says none of this is real.
            default -> new Descriptor(
                    "FAKE", "Synthetic AI", true, "SYNTHETIC", true, NO_MODEL, NO_MODEL);
        };
    }

    /**
     * @param provider    stable machine-readable id: FAKE, GEMINI, ANTHROPIC
     * @param label       provider name for display
     * @param configured  whether a credential is present; never the credential itself
     * @param mode        MODEL when a real provider answers, SYNTHETIC when the fake does
     * @param synthetic   convenience form of {@code mode}, so a client cannot get the test backwards
     * @param fastModel   model id serving the FAST tier
     * @param qualityModel model id serving the QUALITY tier
     */
    public record Descriptor(String provider, String label, boolean configured, String mode,
                             boolean synthetic, String fastModel, String qualityModel) {
    }
}
