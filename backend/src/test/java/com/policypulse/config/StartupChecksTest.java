package com.policypulse.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards that stop a configuration only safe on a laptop from reaching
 * anywhere else.
 *
 * <p>Safe by default is the whole point, so most of these are about what happens
 * when nobody has said anything at all.
 */
class StartupChecksTest {

    private static final String REAL_SECRET = "0f2a7c19e4b8d3516a9c0e7b4d28f1a635c8b90e";
    private static final String SHIPPED_SECRET = "local-dev-only-change-me-use-32-chars-min!!";
    private static final String LOCAL_ORIGINS = "http://localhost:5173";

    private StartupChecks checks(boolean devMode, String secret, String origins, boolean seed) {
        return new StartupChecks(devMode, secret, origins, seed);
    }

    @Test
    void aProperlyConfiguredDeploymentStarts() {
        assertThatCode(() -> checks(false, REAL_SECRET, LOCAL_ORIGINS, false).check())
                .doesNotThrowAnyException();
    }

    /**
     * The shipped key is in this repository, so anybody can mint a token for any
     * account with it. Nothing about that is subtle enough to warn about.
     */
    @Test
    void theShippedSigningKeyIsRefused() {
        assertThatThrownBy(() -> checks(false, SHIPPED_SECRET, LOCAL_ORIGINS, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void aKeyTooShortToBeWorthTheNameIsRefused() {
        assertThatThrownBy(() -> checks(false, "too-short", LOCAL_ORIGINS, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than");
    }

    /** Credentials are allowed on these requests, so a wildcard is not a shortcut. */
    @Test
    void aWildcardCorsOriginIsRefused() {
        assertThatThrownBy(() -> checks(false, REAL_SECRET, "*", false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGINS");
    }

    @Test
    void demoAccountsOnAPublishedPasswordAreRefused() {
        assertThatThrownBy(() -> checks(false, REAL_SECRET, LOCAL_ORIGINS, true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SEED");
    }

    /** Everything wrong at once still names everything, not just the first thing. */
    @Test
    void allTheProblemsAreNamedAtOnce() {
        assertThatThrownBy(() -> checks(false, SHIPPED_SECRET, "*", true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("CORS_ORIGINS")
                .hasMessageContaining("APP_SEED");
    }

    /**
     * Development is the thing you opt into. A deployment that sets nothing gets
     * the safe behaviour, because forgetting the flag is exactly the mistake
     * being guarded against.
     */
    @Test
    void sayingItIsADevelopmentMachineTurnsThemIntoWarnings() {
        assertThatCode(() -> checks(true, SHIPPED_SECRET, "*", true).check())
                .doesNotThrowAnyException();
    }
}
