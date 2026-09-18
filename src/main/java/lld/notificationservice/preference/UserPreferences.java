package lld.notificationservice.preference;

import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.DeviceToken;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-user notification configuration.
 *
 * <p>Sourced from a persistent store (MySQL / DynamoDB) and cached in Redis.
 * All temporal DND logic is evaluated in the user's own timezone to avoid
 * incorrectly blocking notifications for users whose local time is in
 * business hours even while UTC is in the DND window.
 */
public final class UserPreferences {

    private final String userId;
    private final List<DeliveryChannel> channelPriority;  // ordered preference list
    private final Set<DeliveryChannel> optedOutChannels;
    private final LocalTime dndStart;   // nullable → no DND window
    private final LocalTime dndEnd;
    private final ZoneId timezone;
    private final int freqCapPerDay;    // 0 = no cap
    private final String email;
    private final String phoneNumber;
    private final String webhookEndpoint;
    private final List<DeviceToken> deviceTokens;

    private UserPreferences(Builder b) {
        this.userId           = Objects.requireNonNull(b.userId, "userId");
        this.channelPriority  = List.copyOf(b.channelPriority);
        this.optedOutChannels = b.optedOutChannels.isEmpty()
                ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(b.optedOutChannels));
        this.dndStart         = b.dndStart;
        this.dndEnd           = b.dndEnd;
        this.timezone         = b.timezone != null ? b.timezone : ZoneId.of("UTC");
        this.freqCapPerDay    = b.freqCapPerDay;
        this.email            = b.email;
        this.phoneNumber      = b.phoneNumber;
        this.webhookEndpoint  = b.webhookEndpoint;
        this.deviceTokens     = new ArrayList<>(b.deviceTokens);
    }

    public String getUserId()                        { return userId; }
    public List<DeliveryChannel> getChannelPriority(){ return channelPriority; }
    public boolean isOptedOut(DeliveryChannel ch)    { return optedOutChannels.contains(ch); }
    public int getFreqCapPerDay()                    { return freqCapPerDay; }
    public String getEmail()                         { return email; }
    public String getPhoneNumber()                   { return phoneNumber; }
    public String getWebhookEndpoint()               { return webhookEndpoint; }
    public ZoneId getTimezone()                      { return timezone; }

    public List<DeviceToken> getActiveDeviceTokens() {
        return deviceTokens.stream().filter(DeviceToken::isActive).toList();
    }

    /**
     * Returns true if the current instant falls inside the user's DND window.
     * Handles overnight windows (e.g., 22:00–08:00) correctly.
     */
    public boolean isInDnd() {
        if (dndStart == null || dndEnd == null) return false;
        LocalTime now = ZonedDateTime.now(timezone).toLocalTime();
        if (dndStart.isBefore(dndEnd)) {
            return !now.isBefore(dndStart) && now.isBefore(dndEnd);
        }
        // Overnight window: start > end (e.g. 22:00–08:00)
        return !now.isBefore(dndStart) || now.isBefore(dndEnd);
    }

    public static Builder builder(String userId) {
        return new Builder(userId);
    }

    public static final class Builder {
        private final String userId;
        private List<DeliveryChannel> channelPriority = List.of(
                DeliveryChannel.PUSH, DeliveryChannel.EMAIL, DeliveryChannel.SMS);
        private Set<DeliveryChannel> optedOutChannels = EnumSet.noneOf(DeliveryChannel.class);
        private LocalTime dndStart;
        private LocalTime dndEnd;
        private ZoneId timezone;
        private int freqCapPerDay;
        private String email;
        private String phoneNumber;
        private String webhookEndpoint;
        private List<DeviceToken> deviceTokens = new ArrayList<>();

        private Builder(String userId) { this.userId = userId; }

        public Builder channelPriority(List<DeliveryChannel> p) { this.channelPriority = p; return this; }
        public Builder optOut(DeliveryChannel ch)                { this.optedOutChannels.add(ch); return this; }
        public Builder dnd(LocalTime start, LocalTime end)       { this.dndStart = start; this.dndEnd = end; return this; }
        public Builder timezone(ZoneId tz)                       { this.timezone = tz; return this; }
        public Builder freqCapPerDay(int cap)                    { this.freqCapPerDay = cap; return this; }
        public Builder email(String e)                           { this.email = e; return this; }
        public Builder phoneNumber(String p)                     { this.phoneNumber = p; return this; }
        public Builder webhookEndpoint(String url)               { this.webhookEndpoint = url; return this; }
        public Builder deviceToken(DeviceToken t)                { this.deviceTokens.add(t); return this; }

        public UserPreferences build() { return new UserPreferences(this); }
    }
}
