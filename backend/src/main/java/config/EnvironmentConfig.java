package config;

import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Files;
import java.nio.file.Path;

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

    private static Path configuredPath() {
        String configuredPath = System.getProperty("dotenv.path");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("DOTENV_PATH");
        }
        return configuredPath == null || configuredPath.isBlank() ? null : Path.of(configuredPath);
    }

    private static Path searchDirectory(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".env"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
