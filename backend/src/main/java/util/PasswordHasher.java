package util;

import org.mindrot.jbcrypt.BCrypt;

/*
 * Utility class to hash passwords and verify them using BCrypt.
 */
public final class PasswordHasher {

    // Prevent instantiation of utility class
    private PasswordHasher() {
    }

    /*
     * Hashes a plain-text password using BCrypt.
     */
    public static String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /*
     * Checks if a plain-text password matches a hashed password.
     */
    public static boolean matches(String rawPassword, String hash) {
        return BCrypt.checkpw(rawPassword, hash);
    }
}
