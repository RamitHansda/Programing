package lld.notificationservice.model;

import java.time.Instant;
import java.util.Objects;

/** A mobile or web push registration token tied to a user and platform. */
public final class DeviceToken {

    public enum Platform { IOS, ANDROID, WEB }

    private final String token;
    private final Platform platform;
    private final String appVersion;
    private final Instant registeredAt;
    private volatile boolean active;

    public DeviceToken(String token, Platform platform, String appVersion) {
        this.token = Objects.requireNonNull(token, "token");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.appVersion = appVersion;
        this.registeredAt = Instant.now();
        this.active = true;
    }

    public String getToken()         { return token; }
    public Platform getPlatform()    { return platform; }
    public String getAppVersion()    { return appVersion; }
    public Instant getRegisteredAt() { return registeredAt; }
    public boolean isActive()        { return active; }

    public void deactivate()         { this.active = false; }

    @Override
    public String toString() {
        return "DeviceToken{token=" + token.substring(0, Math.min(8, token.length()))
                + "..., platform=" + platform + ", active=" + active + "}";
    }
}
