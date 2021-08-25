package com.redhat.cloud.notifications.db;

import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.ConfigProvider;
import org.hibernate.reactive.mutiny.Mutiny;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static java.util.concurrent.TimeUnit.MINUTES;

@ApplicationScoped
public class EventLogCleaner {

    public static final String EVENT_LOG_CLEANER_DELETE_AFTER_CONF_KEY = "event-log-cleaner.delete-after";

    private static final Logger LOGGER = Logger.getLogger(EventLogCleaner.class);
    private static final Duration DEFAULT_DELETE_DELAY = Duration.ofDays(14L);

    @Inject
    Mutiny.StatelessSession statelessSession;

    /**
     * The event log entries are stored in the database until their retention time is reached.
     * This scheduled job deletes from the database the expired event log entries.
     */
    /*
     * TODO The scheduling is delayed to prevent an unwanted execution during tests. Remove the delay and set the period
     * to `disabled` after the Quarkus 2 bump. See https://quarkus.io/guides/scheduler-reference for more details.
     */
    @Scheduled(identity = "EventLogCleaner", delay = 10L, delayUnit = MINUTES, every = "{event-log-cleaner.period}")
    public void clean() {
        Duration deleteDelay = ConfigProvider.getConfig().getOptionalValue(EVENT_LOG_CLEANER_DELETE_AFTER_CONF_KEY, Duration.class)
                .orElse(DEFAULT_DELETE_DELAY);
        LocalDateTime deleteBefore = now().minus(deleteDelay);
        LOGGER.infof("Event log purge starting. Entries older than %s will be deleted.", deleteBefore.toString());
        statelessSession.createQuery("DELETE FROM Event WHERE created < :deleteBefore")
                .setParameter("deleteBefore", deleteBefore)
                .executeUpdate()
                .onItem().invoke(deleted -> LOGGER.infof("%d entries were deleted from the database.", deleted))
                .await().indefinitely();
        LOGGER.info("Event log purge ended.");
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
