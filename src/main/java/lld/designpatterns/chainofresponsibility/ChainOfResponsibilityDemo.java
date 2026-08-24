package lld.designpatterns.chainofresponsibility;

import lld.designpatterns.chainofresponsibility.LogHandler.LogMessage;
import lld.designpatterns.chainofresponsibility.LogHandler.LogMessage.Level;

/**
 * Demo: console → file → error-alert pipeline via {@link ChainBuilder}.
 */
public final class ChainOfResponsibilityDemo {

    public static void main(String[] args) {
        LogHandler chain = ChainBuilder
                .start(new ConsoleLogHandler())
                .then(new FileLogHandler("/tmp/app.log"))
                .then(new ErrorAlertHandler())
                .build();

        long now = System.currentTimeMillis();
        chain.handle(new LogMessage(Level.INFO, "api", "request ok", now));
        chain.handle(new LogMessage(Level.ERROR, "api", "db timeout", now));
    }

    private ChainOfResponsibilityDemo() {}
}
