package lld.jenkinslike;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory Jenkins-like coordinator with a FIFO queue and labeled build agents.
 */
public class InMemoryBuildScheduler implements BuildScheduler {
    private static final QueuedBuild POISON = QueuedBuild.poison();

    private final BlockingQueue<QueuedBuild> queue = new LinkedBlockingQueue<>();
    private final Map<Long, BuildRun> runs = new ConcurrentHashMap<>();
    private final Map<String, PipelineDefinition> jobs = new ConcurrentHashMap<>();
    private final List<Agent> agents;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public InMemoryBuildScheduler(List<Agent> agents) {
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("at least one agent is required");
        }
        this.agents = List.copyOf(agents);
        for (Agent agent : agents) {
            Thread worker = new Thread(() -> workerLoop(agent), "jenkinslike-" + agent.getId());
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    public static InMemoryBuildScheduler withSingleAgent(String id, Set<String> labels) {
        return new InMemoryBuildScheduler(List.of(new Agent(id, labels)));
    }

    @Override
    public void registerJob(PipelineDefinition pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        validateAgentCapacity(pipeline);
        jobs.put(pipeline.getJobName(), pipeline);
    }

    @Override
    public BuildRun submit(PipelineDefinition pipeline) {
        return submit(pipeline, Map.of());
    }

    @Override
    public BuildRun submit(PipelineDefinition pipeline, Map<String, String> parameters) {
        Objects.requireNonNull(pipeline, "pipeline");
        validateCanAccept(pipeline);
        return enqueue(pipeline, parameters);
    }

    @Override
    public BuildRun triggerJob(String jobName) {
        return triggerJob(jobName, Map.of());
    }

    @Override
    public BuildRun triggerJob(String jobName, Map<String, String> parameters) {
        PipelineDefinition pipeline = jobs.get(jobName);
        if (pipeline == null) {
            throw new BuildException("job is not registered: " + jobName);
        }
        validateCanAccept(pipeline);
        return enqueue(pipeline, parameters);
    }

    private BuildRun enqueue(PipelineDefinition pipeline, Map<String, String> parameters) {
        long buildNumber = sequence.incrementAndGet();
        BuildRun run = new BuildRun(buildNumber, pipeline);
        runs.put(buildNumber, run);
        queue.add(new QueuedBuild(buildNumber, pipeline, parameters == null ? Map.of() : parameters, Instant.now()));
        run.log("Build queued for job " + pipeline.getJobName());
        return run;
    }

    @Override
    public Optional<BuildRun> findBuild(long buildNumber) {
        return Optional.ofNullable(runs.get(buildNumber));
    }

    @Override
    public List<BuildRun> listBuilds() {
        List<BuildRun> snapshot = new ArrayList<>(runs.values());
        snapshot.sort((left, right) -> Long.compare(left.getBuildNumber(), right.getBuildNumber()));
        return Collections.unmodifiableList(snapshot);
    }

    @Override
    public boolean cancel(long buildNumber) {
        BuildRun run = runs.get(buildNumber);
        if (run == null) {
            return false;
        }
        return run.cancel();
    }

    @Override
    public int queuedBuildCount() {
        return queue.size();
    }

    @Override
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            for (int i = 0; i < workers.size(); i++) {
                queue.offer(POISON);
            }
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread worker : workers) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            worker.join(TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        }
        return true;
    }

    private void workerLoop(Agent agent) {
        while (true) {
            QueuedBuild queued;
            try {
                queued = takeNextFor(agent);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (queued == POISON) {
                return;
            }
            BuildRun run = runs.get(queued.getBuildNumber());
            if (run == null || run.getStatus() == BuildStatus.CANCELLED) {
                continue;
            }
            execute(agent, queued, run);
        }
    }

    private QueuedBuild takeNextFor(Agent agent) throws InterruptedException {
        List<QueuedBuild> deferred = new ArrayList<>();
        try {
            while (true) {
                QueuedBuild queued = queue.take();
                if (queued == POISON || isRunnableBy(agent, queued)) {
                    return queued;
                }
                deferred.add(queued);

                QueuedBuild next;
                while ((next = queue.poll()) != null) {
                    if (next == POISON || isRunnableBy(agent, next)) {
                        return next;
                    }
                    deferred.add(next);
                }

                requeueAll(deferred);
                deferred.clear();
                Thread.sleep(25);
            }
        } finally {
            requeueAll(deferred);
        }
    }

    private boolean isRunnableBy(Agent agent, QueuedBuild queued) {
        BuildRun run = runs.get(queued.getBuildNumber());
        return run != null
                && run.getStatus() != BuildStatus.CANCELLED
                && agent.canRun(queued.getPipeline());
    }

    private void requeueAll(List<QueuedBuild> queuedBuilds) {
        for (QueuedBuild queued : queuedBuilds) {
            queue.offer(queued);
        }
    }

    private void execute(Agent agent, QueuedBuild queued, BuildRun run) {
        if (!run.markRunning(agent.getId())) {
            return;
        }
        Map<String, String> environment = mergeEnvironment(queued);
        BuildContext context = new BuildContext(
                queued.getPipeline().getJobName(),
                run.getBuildNumber(),
                environment,
                run::log);

        for (Stage stage : queued.getPipeline().getStages()) {
            StageResult stageResult = executeStage(stage, context);
            run.addStageResult(stageResult);
            if (stageResult.getStatus() == StepStatus.FAILED) {
                String failure = stageResult.getSteps().stream()
                        .filter(step -> step.getStatus() == StepStatus.FAILED)
                        .map(StepResult::getErrorMessage)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("stage failed: " + stage.getName());
                run.markFailed(failure);
                return;
            }
        }
        run.markSuccess();
    }

    private StageResult executeStage(Stage stage, BuildContext context) {
        StageResult stageResult = new StageResult(stage.getName());
        stageResult.markRunning(Instant.now());
        context.log("Stage started: " + stage.getName());

        for (BuildStep step : stage.getSteps()) {
            Instant started = Instant.now();
            context.log("Step started: " + step.getName());
            try {
                step.execute(context);
                StepResult result = new StepResult(
                        stage.getName(),
                        step.getName(),
                        StepStatus.SUCCESS,
                        Duration.between(started, Instant.now()),
                        null);
                stageResult.addStep(result);
                context.log("Step succeeded: " + step.getName());
            } catch (Exception e) {
                StepResult result = new StepResult(
                        stage.getName(),
                        step.getName(),
                        StepStatus.FAILED,
                        Duration.between(started, Instant.now()),
                        e.getMessage());
                stageResult.addStep(result);
                context.log("Step failed: " + step.getName() + " - " + e.getMessage());
                stageResult.markFinished(StepStatus.FAILED, Instant.now());
                context.log("Stage failed: " + stage.getName());
                return stageResult;
            }
        }

        stageResult.markFinished(StepStatus.SUCCESS, Instant.now());
        context.log("Stage succeeded: " + stage.getName());
        return stageResult;
    }

    private Map<String, String> mergeEnvironment(QueuedBuild queued) {
        Map<String, String> environment = new LinkedHashMap<>(queued.getPipeline().getEnvironment());
        environment.putAll(queued.getParameters());
        environment.put("JOB_NAME", queued.getPipeline().getJobName());
        environment.put("BUILD_NUMBER", String.valueOf(queued.getBuildNumber()));
        return environment;
    }

    private void validateCanAccept(PipelineDefinition pipeline) {
        if (shutdown.get()) {
            throw new BuildException("scheduler is shut down");
        }
        validateAgentCapacity(pipeline);
    }

    private void validateAgentCapacity(PipelineDefinition pipeline) {
        boolean hasCapableAgent = agents.stream().anyMatch(agent -> agent.canRun(pipeline));
        if (!hasCapableAgent) {
            throw new BuildException("no agent can run labels " + pipeline.getRequiredLabels());
        }
    }
}
