package interview.sensehq;

public class ChannelFactory {
    public Channel getChannel(ChannelType channelType) throws Exception {
        if(channelType == ChannelType.WHATSAPP)
            return new WhatsAppChannel();
        else if (channelType == ChannelType.EMAIL)
            return new EmailChannel();
        else if (channelType == ChannelType.PUSH_NOTIFICATION)
            return new PushChannel();
        else if(channelType == ChannelType.SMS)
            return new SMSChannel();
        else throw new Exception("Not implmented");
    }
}
