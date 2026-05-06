package lld.jenkinslike;

@FunctionalInterface
interface BuildLogger {
    void log(String message);
}
