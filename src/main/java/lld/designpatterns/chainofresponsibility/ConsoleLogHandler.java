package lld.designpatterns.chainofresponsibility;

public final class ConsoleLogHandler extends LogHandler {

    @Override
    protected boolean handleMessage(LogMessage message) {
        System.out.println("[CONSOLE] " + message.level() + " " + message.source() + " " + message.text());
        return true; // we always "handle" by printing; chain could continue for other handlers
    }
}
