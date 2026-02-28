package lld.designpatterns.mediator;

import java.util.Objects;

/**
 * Colleague: does not message others directly; sends via ChatRoom (mediator).
 */
public class ChatUser {

    private final String userId;
    private final String displayName;
    private ChatRoom mediator;

    public ChatUser(String userId, String displayName) {
        this.userId = Objects.requireNonNull(userId);
        this.displayName = displayName != null ? displayName : userId;
    }

    void setMediator(ChatRoom room) {
        this.mediator = room;
    }

    public void send(String text) {
        if (mediator == null) return;
        mediator.broadcast(this, text);
    }

    /** Called by mediator when another user sends a message. */
    public void receive(String fromUserId, String text) {
        // In production: push to UI or queue
    }

    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
}
