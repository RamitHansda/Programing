package interview.sensehq;

import java.util.HashMap;
import java.util.Map;

public class EmailChannel implements Channel{

    // email sending client
    @Override
    public boolean sendNotification(String sender, String receiver, NotificationType notificationType, TemplateType templateType, HashMap<String, String> varMap) {


        String message = "Hi $user , Your order has been placed with $no_of_items items";
        for (Map.Entry<String, String> entry: varMap.entrySet()) {
            message = message.replaceAll("\\$" + entry.getKey(), entry.getValue());
        }

        System.out.println(message);
        return true;
    }
}
