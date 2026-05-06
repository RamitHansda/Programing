package lld.jenkinslike;

@FunctionalInterface
public interface BuildStepAction {
    void execute(BuildContext context) throws Exception;
}
