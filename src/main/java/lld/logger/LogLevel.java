package lld.logger;

public enum LogLevel {
    DEBUG(1), INFO(2), WARNING(3), ERROR(4);

    final int priority;
    LogLevel(int priority){
        this.priority = priority;
    }
}
