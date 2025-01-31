package com.redhat.cloud.notifications.connector.email.processors.recipients;

import com.redhat.cloud.notifications.connector.email.constants.ExchangeProperty;
import com.redhat.cloud.notifications.connector.email.model.settings.RecipientSettings;
import com.redhat.cloud.notifications.connector.email.model.settings.User;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class RecipientsFilter implements Processor {
    /**
     * Filters the fetched users according to the criteria specified in the
     * recipient settings.
     * @param exchange the exchange of the pipeline.
     */
    @Override
    public void process(final Exchange exchange) {
        // Fetch the required data to filter the users.
        final RecipientSettings recipientSettings = exchange.getProperty(ExchangeProperty.CURRENT_RECIPIENT_SETTINGS, RecipientSettings.class);
        final Set<String> subscribers = (Set<String>) exchange.getProperty(ExchangeProperty.SUBSCRIBERS, Set.class);
        final Set<User> users = exchange.getProperty(ExchangeProperty.USERS, Set.class);
        // Use a new hash set to hold the users. The "users" set that
        // comes from the user providers' responses is cached, but if we modify
        // it directly, it also affects the cached list.
        final Set<User> filteredUsers = new HashSet<>(users);

        // If the request settings contains a list of usernames, then the
        // recipients from RBAC who are not included in the request users list
        // are filtered out. Otherwise, the full list of recipients from RBAC
        // will be processed by the next step.
        if (recipientSettings.getUsers() != null && recipientSettings.getUsers().size() > 0) {
            filteredUsers.removeIf(user -> !recipientSettings.getUsers().contains(user.getUsername()));
        }

        // If the user preferences should be ignored, then we don't further
        // filter the recipients. Otherwise, we need to make sure that we only
        // send the notification to the subscribers of the event type.
        if (!recipientSettings.isIgnoreUserPreferences()) {
            filteredUsers.removeIf(user -> !subscribers.contains(user.getUsername()));
        }

        exchange.setProperty(ExchangeProperty.FILTERED_USERS, filteredUsers);
    }
}
