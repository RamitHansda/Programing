package lld.designpatterns.mediator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mediator: central hub. Users send messages to the room; room broadcasts to others (with optional rules).
 */
public final class ChatRoom {

    private final String roomId;
    private final List<ChatUser> users = new CopyOnWriteArrayList<>();
    private final List<MessageRule> rules = new ArrayList<>();

    public ChatRoom(String roomId) {
        this.roomId = Objects.requireNonNull(roomId);
    }

    public void addUser(ChatUser user) {
        users.add(Objects.requireNonNull(user));
        user.setMediator(this);
    }

    public void removeUser(ChatUser user) {
        users.remove(user);
        user.setMediator(null);
    }

    public void addRule(MessageRule rule) {
        rules.add(Objects.requireNonNull(rule));
    }

    /**
     * User sends message to room; mediator broadcasts to others (excluding sender) after applying rules.
     */
    public void broadcast(ChatUser sender, String text) {
        for (MessageRule rule : rules) {
            if (!rule.allow(sender, text)) return; // e.g. block list, rate limit
        }
        for (ChatUser u : users) {
            if (u != sender) {
                u.receive(sender.getUserId(), text);
            }
        }
    }

    public String getRoomId() { return roomId; }

    @FunctionalInterface
    public interface MessageRule {
        boolean allow(ChatUser sender, String text);
    }
}
