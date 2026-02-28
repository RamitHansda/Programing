package lld.designpatterns.bridge;

/**
 * TV device implementation (could be Sony, Samsung, etc.).
 */
public final class TV implements Device {

    private final String brand;
    private final String deviceId;
    private boolean on;
    private int channel;

    public TV(String brand, String deviceId) {
        this.brand = brand;
        this.deviceId = deviceId;
    }

    @Override
    public void powerOn() {
        on = true;
        channel = 1;
    }

    @Override
    public void powerOff() {
        on = false;
    }

    @Override
    public boolean isOn() {
        return on;
    }

    @Override
    public String getDeviceId() {
        return deviceId;
    }

    public void setChannel(int ch) {
        this.channel = ch;
    }

    public int getChannel() {
        return channel;
    }

    public String getBrand() {
        return brand;
    }
}
