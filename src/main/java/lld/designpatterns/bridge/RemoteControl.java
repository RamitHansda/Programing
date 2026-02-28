package lld.designpatterns.bridge;

import java.util.Objects;

/**
 * Bridge: abstraction. Buttons (on, off, next) are independent of device implementation.
 */
public abstract class RemoteControl {

    protected final Device device;

    protected RemoteControl(Device device) {
        this.device = Objects.requireNonNull(device);
    }

    public void pressPower() {
        if (device.isOn()) {
            device.powerOff();
        } else {
            device.powerOn();
        }
    }

    public void pressNext() {
        if (device.isOn()) {
            doNext();
        }
    }

    /** Subclasses override for device-specific "next" (channel, track, etc.). */
    protected abstract void doNext();

    public boolean isDeviceOn() {
        return device.isOn();
    }
}
