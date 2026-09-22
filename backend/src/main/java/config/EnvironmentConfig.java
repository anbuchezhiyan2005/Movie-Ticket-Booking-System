package config;

import io.github.cdimascio.dotenv.Dotenv; // used for loading environment variables from a .env file

import java.nio.file.Files; // used for checking if a file exists
import java.nio.file.Path; // used for file path manipulation

public final class EnvironmentConfig {

    private static Dotenv dotenv;

    private EnvironmentConfig() {
    }

    public static synchronized void init() {
        if (dotenv != null) {
            return;
        }

        Path dotenvPath = configuredPath();
        if (dotenvPath != null && Files.isRegularFile(dotenvPath)) {
            // Load the .env file from the specified path
            dotenv = Dotenv.configure()
                    .directory(dotenvPath.getParent().toString())
                    .filename(dotenvPath.getFileName().toString())
                    .ignoreIfMissing()
                    .load();
            return;
        }

        Path directory = searchDirectory(Path.of(System.getProperty("user.dir")));
        dotenv = Dotenv.configure()
                .directory(directory == null ? System.getProperty("user.dir") : directory.toString())
                .ignoreIfMissing()
                .load();
    }

    public static String get(String name) {
        return get(name, null);
    }

    public static String get(String name, String defaultValue) {
        init();

        String systemValue = System.getProperty(name);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }

        String environmentValue = System.getenv(name);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }

        String dotenvValue = dotenv.get(name);
        return dotenvValue == null || dotenvValue.isBlank() ? defaultValue : dotenvValue;
    }

    // Get the configured path for the .env file, either from system properties or environment variables
    private static Path configuredPath() {
        String configuredPath = System.getProperty("dotenv.path");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("DOTENV_PATH");
        }
        return configuredPath == null || configuredPath.isBlank() ? null : Path.of(configuredPath);
    }

    // Search for a .env file starting from the given directory and moving up the directory tree
    private static Path searchDirectory(Path start) {
        // convert the starting path to an absolute and normalized path
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            // Checks if a .env file exists in the current directory
            if (Files.isRegularFile(current.resolve(".env"))) {
                return current;
            }
            // Move up to the parent directory
            current = current.getParent();
        }
        return null;
    }
}
