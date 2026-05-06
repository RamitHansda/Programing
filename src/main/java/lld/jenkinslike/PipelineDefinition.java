package lld.jenkinslike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable job definition: agent requirements, environment and ordered stages.
 */
public class PipelineDefinition {
    private final String jobName;
    private final List<Stage> stages;
    private final Map<String, String> environment;
    private final Set<String> requiredLabels;

    private PipelineDefinition(Builder builder) {
        if (builder.jobName == null || builder.jobName.isBlank()) {
            throw new IllegalArgumentException("jobName is required");
        }
        if (builder.stages.isEmpty()) {
            throw new IllegalArgumentException("at least one stage is required");
        }
        this.jobName = builder.jobName;
        this.stages = Collections.unmodifiableList(new ArrayList<>(builder.stages));
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.environment));
        this.requiredLabels = Collections.unmodifiableSet(new HashSet<>(builder.requiredLabels));
    }

    public static Builder builder(String jobName) {
        return new Builder(jobName);
    }

    public String getJobName() {
        return jobName;
    }

    public List<Stage> getStages() {
        return stages;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public Set<String> getRequiredLabels() {
        return requiredLabels;
    }

    public static class Builder {
        private final String jobName;
        private final List<Stage> stages = new ArrayList<>();
        private final Map<String, String> environment = new LinkedHashMap<>();
        private final Set<String> requiredLabels = new HashSet<>();

        private Builder(String jobName) {
            this.jobName = jobName;
        }

        public Builder addStage(Stage stage) {
            if (stage == null) {
                throw new NullPointerException("stage is null");
            }
            stages.add(stage);
            return this;
        }

        public Builder env(String key, String value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("environment key is required");
            }
            environment.put(key, value);
            return this;
        }

        public Builder requiredLabel(String requiredLabel) {
            if (requiredLabel == null || requiredLabel.isBlank()) {
                throw new IllegalArgumentException("required label is required");
            }
            requiredLabels.add(requiredLabel);
            return this;
        }

        public Builder requiredLabels(Set<String> labels) {
            if (labels == null) {
                throw new NullPointerException("labels is null");
            }
            labels.forEach(this::requiredLabel);
            return this;
        }

        public PipelineDefinition build() {
            return new PipelineDefinition(this);
        }
    }
}
