package server.webserver.api;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.tools.Config;

/**
 * Authentication / authorization for the web admin API.
 *
 * <p>The collaborator-submitted prototype shipped with <b>zero</b>
 * authentication — any client could mutate live game state. This class
 * enforces a mandatory credential on every {@code /api/admin} call.
 *
 * <p>Two accepted credential forms (either is sufficient):
 * <ul>
 *   <li><b>Shared token</b> — the {@code WebAdminToken} value from
 *       {@code ceres.cfg}, presented as the {@code X-Admin-Token}
 *       header or a {@code "token"} field in the JSON body.</li>
 *   <li><b>Admin account</b> — username/password of an account whose
 *       status is {@link Account#STATUS_ADMIN}, presented as the
 *       {@code X-Admin-Account} / {@code X-Admin-Pass} headers.</li>
 * </ul>
 *
 * <p><b>Fail-closed:</b> if {@code WebAdminToken} is unset, blank, or
 * still the shipped placeholder, token auth is disabled. With no usable
 * admin account either, the API rejects everything ({@link
 * Decision#DISABLED}). This prevents accidentally exposing an
 * unauthenticated admin surface.
 */
public final class AdminAuth {

    private AdminAuth() {}

    /** Placeholder value shipped in {@code ceres.cfg}; treated as "unset". */
    static final String PLACEHOLDER = "changeme-please-set-a-strong-secret";

    public enum Decision {
        /** Credentials valid — request may proceed. */
        OK,
        /** Credentials supplied but wrong → HTTP 401. */
        UNAUTHORIZED,
        /** No admin auth configured at all → HTTP 403 (API closed). */
        DISABLED
    }

    /** Returns the configured token, or {@code null} if effectively unset. */
    static String configuredToken() {
        String t = Config.getProperty("WebAdminToken");
        if (t == null) return null;
        t = t.trim();
        if (t.isEmpty() || t.equals(PLACEHOLDER)) return null;
        return t;
    }

    /**
     * Is any admin credential configured at all? When false the API is
     * fully closed regardless of what the caller sends.
     */
    static boolean apiEnabled() {
        if (configuredToken() != null) return true;
        // An admin account also enables credential-based access.
        try {
            for (Account a : AccountManager.getAccounts()) {
                if (a.isAdmin()) return true;
            }
        } catch (RuntimeException ignore) {
            // Account store not loaded yet (e.g. early startup / tests):
            // treat as "no admin account configured".
        }
        return false;
    }

    /**
     * Validate a request's credentials.
     *
     * @param token       value of X-Admin-Token header or body "token"
     * @param accountUser value of X-Admin-Account header (may be null)
     * @param accountPass value of X-Admin-Pass header (may be null)
     */
    public static Decision authorize(String token, String accountUser,
            String accountPass) {
        if (!apiEnabled()) return Decision.DISABLED;

        String expected = configuredToken();
        if (expected != null && token != null
                && constantTimeEquals(expected, token.trim())) {
            return Decision.OK;
        }

        if (accountUser != null && accountPass != null) {
            Account a = AccountManager.findByUsername(accountUser);
            if (a != null && a.isAdmin() && !a.isBanned()
                    && a.ckeckPassword(accountPass)) {
                return Decision.OK;
            }
        }

        return Decision.UNAUTHORIZED;
    }

    /** Length-independent constant-time string compare. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i % Math.max(1, y.length)];
        }
        return diff == 0;
    }
}
