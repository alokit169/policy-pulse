package com.policypulse.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changing your own password.
 *
 * <p>The current one is required even though the caller is already signed in: a
 * token left open on a borrowed laptop should not be enough to take the account
 * away from its owner.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Your current password is required")
        String currentPassword,

        /**
         * Length is the only rule. Composition rules push people towards
         * predictable substitutions and away from long passphrases, which are
         * the thing actually worth encouraging.
         */
        @NotBlank(message = "A new password is required")
        @Size(min = 12, max = 200, message = "A new password must be at least 12 characters")
        String newPassword) {
}
