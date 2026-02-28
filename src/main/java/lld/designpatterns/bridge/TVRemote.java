package lld.designpatterns.bridge;

/**
 * Remote for TV: "next" means next channel.
 */
public final class TVRemote extends RemoteControl {

    public TVRemote(Device device) {
        super(device);
    }

    @Override
    protected void doNext() {
        if (device instanceof TV tv) {
            tv.setChannel(tv.getChannel() + 1);
        }
    }
}
