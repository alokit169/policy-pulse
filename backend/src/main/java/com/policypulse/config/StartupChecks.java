package com.policypulse.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start on a configuration that is only safe on a laptop.
 *
 * <p>Safe by default, and development is the thing you opt into. The other way
 * round — a production flag you must remember to set — protects nobody, because
 * forgetting it is exactly the mistake being guarded against, and the punishment
 * for forgetting is silence.
 *
 * <p>So {@code APP_DEV_MODE=true} turns each of these into a warning, and docker
 * compose sets it for local runs. Anywhere it is not set, a deployment carrying
 * the shipped signing key, or a wildcard CORS origin, or demo accounts on a
 * well-known password, stops with a message saying which.
 */
@Component
public class StartupChecks {
    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    /**
     * The shipped signing key names itself. Checking for the marker rather than
     * the whole string means the default can be reworded without the guard
     * quietly ceasing to recognise it.
     */
    static final String DEV_SECRET_MARKER = "local-dev-only";

    /** HS256 needs a key at least this long to be worth the name. */
    static final int MINIMUM_SECRET_LENGTH = 32;

    private final boolean devMode;
    private final String jwtSecret;
    private final String corsOrigins;
    private final boolean seed;

    public StartupChecks(@Value("${app.dev-mode:false}") boolean devMode,
                         @Value("${app.jwt.secret}") String jwtSecret,
                         @Value("${app.cors.allowed-origins:}") String corsOrigins,
                         @Value("${app.seed:false}") boolean seed) {
        this.devMode = devMode;
        this.jwtSecret = jwtSecret;
        this.corsOrigins = corsOrigins;
        this.seed = seed;
    }

    @PostConstruct
    void check() {
        List<String> problems = new ArrayList<>();

        if (jwtSecret.contains(DEV_SECRET_MARKER)) {
            problems.add("JWT_SECRET is the key shipped in application.yml, which is public. "
                    + "Anyone can mint a token for any account with it.");
        }
        if (jwtSecret.length() < MINIMUM_SECRET_LENGTH) {
            problems.add("JWT_SECRET is shorter than " + MINIMUM_SECRET_LENGTH + " characters.");
        }
        if (corsOrigins.contains("*")) {
            problems.add("CORS_ORIGINS contains a wildcard. Credentials are allowed on these "
                    + "requests, so any site could make them on a signed-in user's behalf.");
        }
        if (seed) {
            problems.add("APP_SEED creates demo accounts on a password published in this "
                    + "repository.");
        }

        if (problems.isEmpty()) {
            return;
        }

        if (devMode) {
            problems.forEach(problem -> log.warn("Development configuration: {}", problem));
            log.warn("APP_DEV_MODE is on, so these are warnings. Never set it outside development.");
            return;
        }

        problems.forEach(problem -> log.error("Unsafe configuration: {}", problem));
        throw new IllegalStateException(
                "Refusing to start: " + String.join(" ", problems)
                        + " Set APP_DEV_MODE=true if this really is a development machine.");
    }
}
