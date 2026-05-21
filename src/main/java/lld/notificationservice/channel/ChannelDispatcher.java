package lld.notificationservice.channel;

import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.RenderedContent;
import lld.notificationservice.preference.UserPreferences;

/**
 * Abstraction over a single delivery channel (push / email / SMS / webhook).
 * Each implementation encapsulates one or more provider clients and applies
 * channel-specific error classification.
 *
 * <p>Implementations must be thread-safe.
 */
public interface ChannelDispatcher {

    /** The channel this dispatcher handles. */
    DeliveryChannel getChannel();

    /**
     * Dispatch a notification to the provider and return the classified outcome.
     *
     * @param notification  enriched notification to send
     * @param prefs         resolved user preferences (provides device tokens, email, phone)
     * @param content       pre-rendered channel-specific content
     * @return outcome, never null
     */
    DispatchResult dispatch(Notification notification, UserPreferences prefs, RenderedContent content);
}
