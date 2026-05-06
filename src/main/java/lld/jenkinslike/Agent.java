package lld.jenkinslike;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Worker capability descriptor. A build can run on an agent when all required
 * labels are present on the agent.
 */
public final class Agent {
    private final String id;
    private final Set<String> labels;

    public Agent(String id, Set<String> labels) {
        this.id = requireText(id, "id");
        this.labels = Collections.unmodifiableSet(new HashSet<>(Objects.requireNonNull(labels, "labels")));
    }

    public String getId() {
        return id;
    }

    public Set<String> getLabels() {
        return labels;
    }

    public boolean canRun(PipelineDefinition pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        return labels.containsAll(pipeline.getRequiredLabels());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
