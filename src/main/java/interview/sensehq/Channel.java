package interview.sensehq;

import java.util.HashMap;

public interface Channel {
    boolean sendNotification(String sender, String receiver, NotificationType notificationType, TemplateType templateType, HashMap<String, String> varMap);
}
