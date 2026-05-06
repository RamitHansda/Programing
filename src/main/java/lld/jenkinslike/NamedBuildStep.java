package lld.jenkinslike;

import java.util.Objects;

final class NamedBuildStep implements BuildStep {
    private final String name;
    private final BuildStepAction action;

    NamedBuildStep(String name, BuildStepAction action) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("step name is required");
        }
        this.name = name;
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    public void execute(BuildContext context) throws Exception {
        action.execute(context);
    }

    @Override
    public String getName() {
        return name;
    }
}
