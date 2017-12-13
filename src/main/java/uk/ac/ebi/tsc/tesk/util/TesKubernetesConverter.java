package uk.ac.ebi.tsc.tesk.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.kubernetes.client.models.*;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import uk.ac.ebi.tsc.tesk.model.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static uk.ac.ebi.tsc.tesk.util.KubernetesConstants.*;

/**
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 *
 *     Conversion of TES objects to and from Kubernetes Objects
 */
@Component
public class TesKubernetesConverter {

    private final static Logger logger = LoggerFactory.getLogger(TesKubernetesConverter.class);

    private final Supplier<V1Job> executorTemplateSupplier;

    private final Supplier<V1Job> taskmasterTemplateSupplier;

    private final JobNameGenerator jobNameGenerator;

    private final ObjectMapper objectMapper;

    private final Gson gson;

    private enum JOB_STATUS {ACTIVE, SUCCEEDED, FAILED}

    public TesKubernetesConverter(@Qualifier("executor") Supplier<V1Job> executorTemplateSupplier, @Qualifier("taskmaster")
            Supplier<V1Job> taskmasterTemplateSupplier, JobNameGenerator jobNameGenerator, ObjectMapper objectMapper, Gson gson) {
        this.executorTemplateSupplier = executorTemplateSupplier;
        this.taskmasterTemplateSupplier = taskmasterTemplateSupplier;
        this.jobNameGenerator = jobNameGenerator;
        this.objectMapper = objectMapper;
        this.gson = gson;
    }

    /**
     * Changes job name
     * @param job - input job
     * @param newName - new name
     */
    private void changeJobName(V1Job job, String newName) {
        job.getMetadata().name(newName);
        job.getSpec().getTemplate().getMetadata().name(newName);
        job.getSpec().getTemplate().getSpec().getContainers().get(0).setName(newName);
    }

    /**
     * Converts TES task to Job object with random generated name
     * @param task - TES Task input object
     * @return K8s Job Object
     */
    @SuppressWarnings("unchecked")
    public V1Job fromTesTaskToK8sJob(TesTask task) {
        //get new Job template with random generated name;
        V1Job taskMasterJob = this.taskmasterTemplateSupplier.get();
        //put input task name as annotation
        taskMasterJob.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, task.getName());
        try {
            //in order to retrieve task details, when querying for task details, whole tesTask object is placed as taskMaster's annotation
            //Jackson for TES objects - because, we rely on auto-generated annotations for Json mapping
            taskMasterJob.getMetadata().putAnnotationsItem(ANN_JSON_INPUT_KEY, this.objectMapper.writeValueAsString(task));
        } catch (JsonProcessingException ex) {
            logger.info(String.format("Serializing task %s to JSON failed", taskMasterJob.getMetadata().getName()), ex);
        }
        //Converting executors to Kubernetes Job Objects
        List<V1Job> executorsAsJobs = IntStream.range(0, task.getExecutors().size()).
                mapToObj(i -> this.fromTesExecutorToK8sJob(taskMasterJob.getMetadata().getName(), task.getName(), task.getExecutors().get(i), i, task.getResources())).
                collect(Collectors.toList());
        Map<String, Object> taskMasterInput = new HashMap<>();
        taskMasterInput.put(TASKMASTER_INPUT_EXEC_KEY, executorsAsJobs);
        try {
            //converting original inputs, outputs, volumes and disk size back again to JSON (will be part of taskMaster's input parameter)
            //Jackson - for TES objects
            String jobAsJson = this.objectMapper.writeValueAsString(new TesTask().inputs(task.getInputs()).outputs(task.getOutputs()).volumes(task.getVolumes()).
                    resources(new TesResources().diskGb(Optional.ofNullable(task.getResources()).map(TesResources::getDiskGb).orElse(null))));
            //merging 2 JSONs together into one map
            Map<String, Object> jobAsMap = gson.fromJson(jobAsJson, Map.class);
            taskMasterInput.putAll(jobAsMap);
        } catch (JsonProcessingException e) {
            logger.info(String.format("Serializing copy of task %s to JSON failed", taskMasterJob.getMetadata().getName()), e);
            //TODO throw
        }
        String taskMasterInputAsJSON = this.gson.toJson(taskMasterInput);
        //placing taskmaster's parameter (JSONed map of: inputs, outputs, volumes, executors (as jobs) into ENV variable in taskmaster spec
        taskMasterJob.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().filter(x -> x.getName().equals(TASKMASTER_INPUT)).forEach(x -> x.setValue(taskMasterInputAsJSON));
        return taskMasterJob;
    }

    /**
     * Converts TES executor to K8s Job, that is passed to taskMaster
     * as part of input parameters
     * Name of executor job relies on taskMaster job's name (taskmaster's jobs name + constant suffix)
     * @param generatedTaskId - random generated job's name == task id
     * @param tesTaskName - input task name
     * @param executor - TES executor input object
     * @param executorIndex - ordinal number of executor
     * @param resources - input task resources
     * @return - executor K8s job object. To be placed in taskMaster input JSON map in the list of executors
     */
    public V1Job fromTesExecutorToK8sJob(String generatedTaskId, String tesTaskName, TesExecutor executor, int executorIndex, TesResources resources) {
        //gets template executor Job object
        V1Job job = executorTemplateSupplier.get();
        //set executors name based on taskmaster's job name
        this.changeJobName(job, this.jobNameGenerator.getExecutorName(generatedTaskId, executorIndex));
        //put arbitrary labels and annotations:
        //taskId - to search for executors of a given task
        job.getMetadata().putLabelsItem(LABEL_TESTASK_ID_KEY, generatedTaskId);
        job.getMetadata().putLabelsItem(LABEL_EXECNO_KEY, Integer.valueOf(executorIndex).toString());
        job.getMetadata().putAnnotationsItem(ANN_TESTASK_NAME_KEY, tesTaskName);
        V1Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        container.image(executor.getImage());
        //Should we map executor's command to job container's command (==ENTRYPOINT) or job container's args (==CMD)?
        //At the moment args.
        executor.getCommand().forEach(container::addArgsItem);
        if (executor.getEnv() != null) {
            executor.getEnv().forEach((key, value) -> container.addEnvItem(new V1EnvVar().name(key).value(value)));
        }
        container.setWorkingDir(executor.getWorkdir());
        Optional.ofNullable(resources).map(TesResources::getCpuCores).ifPresent(cpuCores -> container.getResources().putRequestsItem(RESOURCE_CPU_KEY, cpuCores.toString()));
        Optional.ofNullable(resources).map(TesResources::getRamGb).ifPresent(ramGb -> container.getResources().putRequestsItem(RESOURCE_MEM_KEY, ramGb.toString() + RESOURCE_MEM_UNIT));
        return job;
    }

    /**
     * Retrieves TesCreateTaskResponse from K8s job
     * At the moment - only wraps job's name/task's id
     * @param job - K8s taskMaster job
     * @return - TesCreateTaskResponse wrapping task ID
     */
    public TesCreateTaskResponse fromK8sJobToTesCreateTaskResponse(V1Job job) {
        return new TesCreateTaskResponse().id(job.getMetadata().getName());
    }

    /**
     * Resolver of K8s V1JobStatus object.
     * Tests if job is in a given state
     * @param testedObject - V1JobStatus of a job
     * @param testObjective - status to be checked against
     * @return - if Job is in the given status
     */
    private boolean isJobInStatus(V1JobStatus testedObject, JOB_STATUS testObjective) {
        Integer result = null;
        switch (testObjective) {
            case ACTIVE:
                result = testedObject.getActive();
                break;
            case SUCCEEDED:
                result = testedObject.getSucceeded();
                break;
            case FAILED:
                result = testedObject.getFailed();
                break;
        }
        return Optional.ofNullable(result).map(failed -> failed > 0).orElse(false);
    }

    /**
     * Derives TES task's status from taskMasterJob Object and executorJobs object
     * (possibly will need additional processing of executor jobs pods)
     * @param taskMasterJob - taskMaster's job object
     * @param executorJobs - executors' job objects
     * @return TES task status
     */
    public TesState extractStateFromK8sJobs(V1Job taskMasterJob, List<V1Job> executorJobs) {
        String taskMasterJobName = taskMasterJob.getMetadata().getName();
        Optional<V1Job> lastExecutor = executorJobs.stream().max(Comparator.comparing(
                job -> this.jobNameGenerator.extractExecutorNumberFromName(taskMasterJobName, job.getMetadata().getName())));
        boolean taskMasterRunning = this.isJobInStatus(taskMasterJob.getStatus(), JOB_STATUS.ACTIVE);
        boolean taskMasterCompleted = this.isJobInStatus(taskMasterJob.getStatus(), JOB_STATUS.SUCCEEDED);
        boolean executorPresent = lastExecutor.isPresent();
        boolean lastExecutorFailed = executorPresent && this.isJobInStatus(lastExecutor.get().getStatus(), JOB_STATUS.FAILED);
        boolean lastExecutorCompleted = executorPresent && this.isJobInStatus(lastExecutor.get().getStatus(), JOB_STATUS.SUCCEEDED);

        if (taskMasterRunning && !executorPresent) return TesState.INITIALIZING;
        if (taskMasterRunning) return TesState.RUNNING;
        if (taskMasterCompleted && lastExecutorCompleted) return TesState.COMPLETE;
        if (taskMasterCompleted && lastExecutorFailed) return TesState.EXECUTOR_ERROR;
        return TesState.SYSTEM_ERROR;
    }

    /**
     * Extracts TesExecutorLog from executor job and pod objects
     * !! does not contain stdout (which needs access to pod log)
     * @param executorJob - job object
     * @param executorPod - pod object
     * @return - TesExecutorLog object (part of the BASIC output)
     */
    public TesExecutorLog extractExecutorLogFromK8sJobAndPod(V1Job executorJob, V1Pod executorPod) {
        TesExecutorLog log = new TesExecutorLog();
        log.setStartTime(Optional.ofNullable(executorJob.getStatus().getStartTime()).map(time -> ISODateTimeFormat.dateTime().print(time)).orElse(null));
        log.setEndTime(Optional.ofNullable(executorJob.getStatus().getCompletionTime()).map(time -> ISODateTimeFormat.dateTime().print(time)).orElse(null));
        log.setExitCode(Optional.ofNullable(executorPod.getStatus()).
                map(V1PodStatus::getContainerStatuses).
                map(list -> list.size() > 0 ? list.get(0) : null).
                map(V1ContainerStatus::getState).
                map(V1ContainerState::getTerminated).
                map(V1ContainerStateTerminated::getExitCode).
                orElse(null));
        return log;
    }

    /**
     * Extracts minimal view of TesTask from taskMaster's and executors' job objects
     * (will probably need pods, if status needs them)
     */
    public TesTask fromK8sJobsToTesTaskMinimal(V1Job taskMasterJob, List<V1Job> executorJobs) {
        TesTask task = new TesTask();
        task.setId(taskMasterJob.getMetadata().getName());
        task.setState(this.extractStateFromK8sJobs(taskMasterJob, executorJobs));
        return task;
    }
    /**
     * Extracts partial view of TesTask from taskMaster's and executors' job objects
     * without parts that need access to pods and pod logs:
     * TesExecutorLog objects and system_logs
     * (but will probably need pods, if status needs them)
     */
    public TesTask fromK8sJobsToTesTask(V1Job taskMasterJob, List<V1Job> executorJobs, boolean nullifyInputContent) {
        TesTask task = new TesTask();
        String inputJson = Optional.ofNullable(taskMasterJob.getMetadata().getAnnotations().get(ANN_JSON_INPUT_KEY)).orElse("");
        try {
            task = this.objectMapper.readValue(inputJson, TesTask.class);
            if (nullifyInputContent && task.getInputs() != null) {
                task.getInputs().forEach(input -> input.setContent(null));
            }
        } catch (IOException ex) {
            logger.info(String.format("Deserializing task %s from JSON failed", taskMasterJob.getMetadata().getName()), ex);
        }
        task.setId(taskMasterJob.getMetadata().getName());
        task.setState(this.extractStateFromK8sJobs(taskMasterJob, executorJobs));
        task.setCreationTime(ISODateTimeFormat.dateTime().print(taskMasterJob.getMetadata().getCreationTimestamp()));
        TesTaskLog log = new TesTaskLog();
        task.addLogsItem(log);
        log.setStartTime(Optional.ofNullable(taskMasterJob.getStatus().getStartTime()).map(time -> ISODateTimeFormat.dateTime().print(time)).orElse(null));
        log.setEndTime(Optional.ofNullable(taskMasterJob.getStatus().getCompletionTime()).map(time -> ISODateTimeFormat.dateTime().print(time)).orElse(null));
        return task;
    }


}
