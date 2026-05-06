package lld.jenkinslike;

public interface BuildStep {
    void execute(BuildContext context) throws Exception;

    default String getName() {
        return getClass().getSimpleName();
    }

    static BuildStep named(String name, BuildStepAction action) {
        return new NamedBuildStep(name, action);
    }
}
