package interview.sensehq;

import java.util.HashMap;

public class Main {
    public static void main(String... args) {
        ChannelFactory channelFactory = new ChannelFactory();

        try {
            Channel emailChannel=  channelFactory.getChannel(ChannelType.EMAIL);
            HashMap<String, String> varMap = new HashMap<>();
            varMap.put("user", "ramit");
            varMap.put("no_of_items", "3");
            emailChannel.sendNotification("ramit.ju.cse@gmail.com", "ramit@skydo.com", NotificationType.TRANSACTIONAL,TemplateType.ORDER_CONFIRMED, varMap );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
