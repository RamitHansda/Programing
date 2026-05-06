package lld.jenkinslike;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable request accepted by the build queue.
 */
public class QueuedBuild {
    private static final QueuedBuild POISON = new QueuedBuild();

    private final long buildNumber;
    private final PipelineDefinition pipeline;
    private final Map<String, String> parameters;
    private final Instant queuedAt;

    private QueuedBuild() {
        this.buildNumber = -1;
        this.pipeline = null;
        this.parameters = Map.of();
        this.queuedAt = Instant.EPOCH;
    }

    public QueuedBuild(long buildNumber,
                       PipelineDefinition pipeline,
                       Map<String, String> parameters,
                       Instant queuedAt) {
        if (buildNumber <= 0) {
            throw new IllegalArgumentException("buildNumber must be positive");
        }
        this.buildNumber = buildNumber;
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        this.queuedAt = Objects.requireNonNull(queuedAt, "queuedAt");
    }

    static QueuedBuild poison() {
        return POISON;
    }

    public long getBuildNumber() {
        return buildNumber;
    }

    public PipelineDefinition getPipeline() {
        return pipeline;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

}
