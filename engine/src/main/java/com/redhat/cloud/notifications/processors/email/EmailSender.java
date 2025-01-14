package com.redhat.cloud.notifications.processors.email;

import com.redhat.cloud.notifications.config.FeatureFlipper;
import com.redhat.cloud.notifications.events.EventWrapper;
import com.redhat.cloud.notifications.ingress.Action;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.Event;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.models.NotificationHistory;
import com.redhat.cloud.notifications.processors.webclient.BopWebClient;
import com.redhat.cloud.notifications.processors.webhooks.WebhookTypeProcessor;
import com.redhat.cloud.notifications.recipients.User;
import com.redhat.cloud.notifications.recipients.mbop.Constants;
import com.redhat.cloud.notifications.templates.TemplateService;
import com.redhat.cloud.notifications.utils.LineBreakCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.quarkus.qute.TemplateInstance;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class EmailSender {

    static final String BODY_TYPE_HTML = "html";
    static final ZoneOffset UTC = ZoneOffset.UTC;

    @Inject
    @BopWebClient
    WebClient bopWebClient;

    @ConfigProperty(name = "processor.email.bop_url")
    String bopUrl;

    @ConfigProperty(name = "processor.email.bop_apitoken")
    String bopApiToken;

    @ConfigProperty(name = "processor.email.bop_client_id")
    String bopClientId;

    @ConfigProperty(name = "processor.email.bop_env")
    String bopEnv;

    @Inject
    FeatureFlipper featureFlipper;

    @Inject
    WebhookTypeProcessor webhookSender;

    @Inject
    TemplateService templateService;

    @Inject
    MeterRegistry registry;

    private Timer processTime;

    @PostConstruct
    public void init() {
        processTime = registry.timer("processor.email.process-time");
        /*
         * The token value we receive contains a line break because of the standard mime encryption. Gabor Burges tried
         * to remove it but that didn't work, so we have to do it here because Vert.x 4 does not allow line breaks in
         * HTTP headers.
         */
        bopApiToken = LineBreakCleaner.clean(bopApiToken);
    }

    public NotificationHistory sendEmail(Set<User> users, Event event, TemplateInstance subject, TemplateInstance body, boolean persistHistory, Endpoint endpoint) {

        NotificationHistory history = null;
        if (users.isEmpty()) {
            Log.debug("No recipient found for this email");
            return history;
        }

        final HttpRequest<Buffer> bopRequest = this.buildBOPHttpRequest();
        LocalDateTime start = LocalDateTime.now(UTC);

        Timer.Sample processedTimer = Timer.start(registry);

        EventType eventType = event.getEventType();
        String bundleName = "NA";
        String applicationName = "NA";
        if (eventType != null) {
            bundleName = eventType.getApplication().getBundle().getName();
            applicationName = eventType.getApplication().getName();
        } else if (event.getEventWrapper().getEvent() instanceof Action action) {
            bundleName = action.getBundle();
            applicationName = action.getApplication();
        }

        // uses canonical EmailSubscription
        try {

            // TODO Add recipients processing from policies-notifications processing (failed recipients)
            //      by checking the NotificationHistory's details section (if missing payload - fix in WebhookTypeProcessor)

            // TODO If the call fails - we should probably rollback Kafka topic (if BOP is down for example)
            //      also add metrics for these failures

            history = webhookSender.doHttpRequest(
                event, endpoint,
                bopRequest,
                getPayload(users, event.getEventWrapper(), subject, body),
                "POST",
                bopUrl,
                persistHistory);
            return history;
        } catch (Exception e) {
            Log.error("Email sending failed", e);
        } finally {
            processedTimer.stop(registry.timer("processor.email.processed", "bundle", bundleName, "application", applicationName));
            processTime.record(Duration.between(start, LocalDateTime.now(UTC)));
            return history;
        }
    }

    private JsonObject getPayload(Set<User> users, EventWrapper<?, ?> eventWrapper, TemplateInstance subject, TemplateInstance body) {

        String renderedSubject;
        String renderedBody;
        try {
            renderedSubject = templateService.renderTemplate(eventWrapper.getEvent(), subject);
            renderedBody = templateService.renderTemplate(eventWrapper.getEvent(), body);
        } catch (Exception e) {
            Log.warnf(e,
                "Unable to render template for %s.",
                eventWrapper.getKey().toString()
            );
            throw e;
        }
        if (featureFlipper.isSkipBopUsersResolution()) {
            SendEmailsRequest request = new SendEmailsRequest();
            request.addEmail(buildEmail(
                    users,
                    renderedSubject,
                    renderedBody
            ));
            return JsonObject.mapFrom(request);
        } else {
            Emails emails = new Emails();
            emails.addEmail(buildEmail(
                    users,
                    renderedSubject,
                    renderedBody
            ));
            return JsonObject.mapFrom(emails);
        }
    }

    protected HttpRequest<Buffer> buildBOPHttpRequest() {
        return bopWebClient
                .postAbs(bopUrl)
                .putHeader(Constants.MBOP_APITOKEN_HEADER, bopApiToken)
                .putHeader(Constants.MBOP_CLIENT_ID_HEADER, bopClientId)
                .putHeader(Constants.MBOP_ENV_HEADER, bopEnv);
    }

    protected Email buildEmail(Set<User> recipients, String subject, String body) {
        Set<String> usersEmail;
        if (featureFlipper.isSkipBopUsersResolution()) {
            usersEmail = recipients.stream().map(User::getEmail).collect(Collectors.toSet());
        } else {
            usersEmail = recipients.stream().map(User::getUsername).collect(Collectors.toSet());
        }

        Email email = new Email();
        email.setBodyType(BODY_TYPE_HTML);
        email.setBccList(usersEmail);
        email.setSubject(subject);
        email.setBody(body);
        return email;
    }
}
