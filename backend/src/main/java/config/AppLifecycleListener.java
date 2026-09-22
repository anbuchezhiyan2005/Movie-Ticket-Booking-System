package config;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.util.logging.Logger;

public class AppLifecycleListener implements ServletContextListener {

    private static final Logger LOGGER = Logger.getLogger(AppLifecycleListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        EnvironmentConfig.init();
        Database.init();
        LOGGER.info("event=application.started");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        Database.shutdown();
        LOGGER.info("event=application.stopped");
    }
}
