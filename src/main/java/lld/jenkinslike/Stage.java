package lld.jenkinslike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered collection of steps that execute as one named pipeline stage.
 */
public class Stage {
    private final String name;
    private final List<BuildStep> steps;

    public Stage(String name, List<BuildStep> steps) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("stage name is required");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("stage must contain at least one step");
        }
        this.name = name;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.steps.forEach(step -> Objects.requireNonNull(step, "stage step is required"));
    }

    public static Stage of(String name, BuildStep... steps) {
        return new Stage(name, List.of(steps));
    }

    public String getName() {
        return name;
    }

    public List<BuildStep> getSteps() {
        return steps;
    }
}
