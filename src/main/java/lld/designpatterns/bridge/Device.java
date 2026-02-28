package lld.designpatterns.bridge;

/**
 * Bridge: implementation interface. Remotes depend on this, not on concrete device brands.
 */
public interface Device {

    void powerOn();
    void powerOff();
    boolean isOn();
    String getDeviceId();
}
