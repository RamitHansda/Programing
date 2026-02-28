package lld.designpatterns.chainofresponsibility;

public final class ErrorAlertHandler extends LogHandler {

    @Override
    protected boolean handleMessage(LogMessage message) {
        if (message.level() == LogMessage.Level.ERROR) {
            // In production: send to Slack/PagerDuty
            return true;
        }
        return false;
    }
}
