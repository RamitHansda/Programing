package lld.designpatterns.chainofresponsibility;

public final class ErrorAlertHandler extends LogHandler {

    @Override
    protected boolean handleMessage(LogMessage message) {
        if (message.level() != LogMessage.Level.ERROR) {
            return false;
        }
        // In production: send to Slack/PagerDuty
        System.out.println("[ALERT] " + message.source() + " " + message.text());
        return true; // ERROR fully escalated; stop chain
    }
}
