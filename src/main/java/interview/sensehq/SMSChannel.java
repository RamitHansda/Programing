package interview.sensehq;

import java.util.HashMap;

public class SMSChannel implements  Channel{
    @Override
    public boolean sendNotification(String sender, String receiver, NotificationType notificationType, TemplateType templateType, HashMap<String, String> varMap) {
        return false;
    }
}
