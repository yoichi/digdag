package io.digdag.core.agent;

import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.google.inject.Inject;
import com.google.common.base.*;
import com.google.common.collect.*;
import io.digdag.core.config.Config;
import io.digdag.core.config.ConfigException;
import io.digdag.core.config.ConfigFactory;
import io.digdag.core.spi.*;
import io.digdag.core.workflow.TaskCallbackApi;

public class TaskRunner
{
    private final TaskCallbackApi callback;
    private final ConfigFactory cf;
    private final Map<String, TaskExecutorFactory> executorTypes;

    @Inject
    public TaskRunner(TaskCallbackApi callback, ConfigFactory cf, Set<TaskExecutorFactory> factories)
    {
        this.callback = callback;
        this.cf = cf;

        ImmutableMap.Builder<String, TaskExecutorFactory> builder = ImmutableMap.builder();
        for (TaskExecutorFactory factory : factories) {
            builder.put(factory.getType(), factory);
        }
        this.executorTypes = builder.build();
    }

    public void run(TaskRequest request)
    {
        long taskId = request.getTaskInfo().getId();
        Config config = request.getConfig().deepCopy();
        Config nextState = request.getLastStateParams();

        // set task name to thread name so that logger shows it
        try (SetThreadName threadName = new SetThreadName("task-"+request.getTaskInfo().getFullName())) {

            if (!config.has("type")) {
                java.util.Optional<String> commandKey = config.getKeys()
                    .stream()
                    .filter(key -> key.endsWith(">"))
                    .findFirst();
                if (!commandKey.isPresent()) {
                    // TODO warning
                    callback.taskSucceeded(taskId,
                            nextState, cf.create(),
                            TaskReport.empty(cf));
                    return;
                }
                config.set("type", commandKey.get().substring(0, commandKey.get().length() - 1));
                config.set("command", config.get(commandKey.get(), Object.class));
            }
            String type = config.get("type", String.class);

            TaskExecutorFactory factory = executorTypes.get(type);
            if (factory == null) {
                throw new ConfigException("Unknown task type: "+type);
            }
            TaskExecutor executor = factory.newTaskExecutor(
                    TaskRequest.builder()
                        .from(request)
                        .config(config)
                        .build());

            TaskResult result;
            try {
                result = executor.run();
            }
            finally {
                nextState = executor.getStateParams();
            }

            callback.taskSucceeded(taskId,
                    nextState, result.getSubtaskConfig(),
                    result.getReport());

        }
        catch (TaskExecutionException ex) {
            if (ex.getError().isPresent()) {
                callback.taskFailed(taskId,
                        ex.getError().get(), nextState,
                        ex.getRetryInterval());
            }
            else {
                callback.taskPollNext(
                        taskId,
                        nextState, ex.getRetryInterval().get());
            }
        }
        catch (Exception ex) {
            Config error = makeExceptionError(cf, ex);
            callback.taskFailed(taskId,
                    error, nextState,
                    Optional.absent());  // no retry
        }
    }

    public static Config makeExceptionError(ConfigFactory cf, Exception ex)
    {
        return cf.create()
            .set("error", ex.toString())
            .set("stacktrace",
                    Arrays.asList(ex.getStackTrace())
                    .stream()
                    .map(it -> it.toString())
                    .collect(Collectors.joining(", ")));
    }
}