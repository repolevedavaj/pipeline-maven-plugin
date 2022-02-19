package org.jenkinsci.plugins.pipeline.maven.listeners;

import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.apache.commons.lang3.tuple.Pair;
import org.jenkinsci.plugins.pipeline.maven.GlobalPipelineMavenConfig;
import org.jenkinsci.plugins.pipeline.maven.MavenArtifact;
import org.jenkinsci.plugins.pipeline.maven.cause.MavenDependencyAbstractCause;
import org.jenkinsci.plugins.pipeline.maven.cause.MavenDependencyCause;
import org.jenkinsci.plugins.pipeline.maven.cause.MavenDependencyCauseHelper;
import org.jenkinsci.plugins.pipeline.maven.cause.MavenDependencyUpstreamCause;
import org.jenkinsci.plugins.pipeline.maven.cause.OtherMavenDependencyCause;
import org.jenkinsci.plugins.pipeline.maven.trigger.WorkflowJobDependencyTrigger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Trigger downstream pipelines.
 *
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
@Extension
public class DownstreamPipelineTriggerRunListener extends RunListener<WorkflowRun> {

    private final static Logger LOGGER = LoggerFactory.getLogger(DownstreamPipelineTriggerRunListener.class);

    @Inject
    public GlobalPipelineMavenConfig globalPipelineMavenConfig;

    @Override
    public void onCompleted(WorkflowRun upstreamBuild, @Nonnull TaskListener listener) {
        logDebugMessage("onCompleted({})", upstreamBuild);

        long startTimeInNanos = System.nanoTime();

        logDebugMessage(listener, "[withMaven] pipelineGraphPublisher - triggerDownstreamPipelines");

        if (shouldSkipTriggeringDownstreamBuilds(upstreamBuild)) {
            triggerPreviouslyOmittedDownstreamPipelines(upstreamBuild, listener);
            return;
        }

        if (hasInfiniteLoop(upstreamBuild)) {
            logInfoMessage(listener, "[withMaven] WARNING abort infinite build trigger loop. Please consider opening a Jira issue");
            return;
        }

        Pair<Map<String, Set<MavenArtifact>>, Map<String, Set<String>>> collectPipelinesToTriggerResult = collectPipelinesToTrigger(upstreamBuild, listener);
        Map<String, Set<MavenArtifact>> jobsToTrigger = collectPipelinesToTriggerResult.getKey();
        Map<String, Set<String>> omittedPipelineTriggersByPipelineFullname = collectPipelinesToTriggerResult.getValue();

        // note: we could verify that the upstreamBuild.getCauses().getOmittedPipelineFullNames are listed in jobsToTrigger
        triggerPipelines(jobsToTrigger, omittedPipelineTriggersByPipelineFullname, upstreamBuild, listener);

        long durationInMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTimeInNanos, TimeUnit.NANOSECONDS);
        if (durationInMillis > TimeUnit.MILLISECONDS.convert(5, TimeUnit.SECONDS)) {
            logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - completed in {} ms", durationInMillis);
        }
    }

    private boolean hasInfiniteLoop(WorkflowRun upstreamBuild) {
        try {
            this.globalPipelineMavenConfig.getPipelineTriggerService().checkNoInfiniteLoopOfUpstreamCause(upstreamBuild);

            return false;
        } catch (IllegalStateException e) {
            // expected in case of an infinite loop

            return true;
        }
    }

    private void triggerPreviouslyOmittedDownstreamPipelines(WorkflowRun upstreamBuild, TaskListener listener) {
        Map<String, List<MavenDependencyCause>> omittedPipelineFullNamesAndCauses = findOmittedDownstreamPipelines(upstreamBuild);

        if (omittedPipelineFullNamesAndCauses.isEmpty()) {
            logDebugMessage(listener, "[withMaven] Skip triggering downstream jobs for upstream build with ignored result status {}: {}", upstreamBuild, upstreamBuild.getResult());

            return;
        }

        for (Map.Entry<String, List<MavenDependencyCause>> entry : omittedPipelineFullNamesAndCauses.entrySet()) {
            Job<?, ?> omittedPipeline = Jenkins.get().getItemByFullName(entry.getKey(), Job.class);
            if (omittedPipeline == null) {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Illegal state: {} not resolved", entry.getKey());
                continue;
            }

            List<Cause> omittedPipelineTriggerCauses = new ArrayList<>();
            for (MavenDependencyCause cause : entry.getValue()) {
                if (cause instanceof MavenDependencyUpstreamCause) {
                    createTriggerCause(upstreamBuild, omittedPipelineTriggerCauses, (MavenDependencyUpstreamCause) cause);
                } else if (cause instanceof MavenDependencyAbstractCause) {
                    createTriggerCause(listener, omittedPipelineTriggerCauses, (MavenDependencyAbstractCause) cause);
                } else {
                    createTriggerCause(omittedPipelineTriggerCauses, (MavenDependencyAbstractCause) cause);
                }
            }

            // TODO deduplicate pipeline triggers

            // See jenkins.triggers.ReverseBuildTrigger.RunListenerImpl.onCompleted(Run, TaskListener)
            Queue.Item queuedItem = ParameterizedJobMixIn.scheduleBuild2(omittedPipeline, -1, new CauseAction(omittedPipelineTriggerCauses));
            if (queuedItem == null) {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Failure to trigger omitted pipeline {} due to causes {}, invocation rejected", ModelHyperlinkNote.encodeTo(omittedPipeline), omittedPipelineTriggerCauses);
            } else {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Triggering downstream pipeline {} despite build result {} for the upstream causes: {}",
                        ModelHyperlinkNote.encodeTo(omittedPipeline),
                        upstreamBuild.getResult(),
                        omittedPipelineTriggerCauses.stream()
                                .map(Cause::getShortDescription)
                                .collect(Collectors.joining(", "))
                );
            }
        }

    }

    private void createTriggerCause(List<Cause> omittedPipelineTriggerCauses, MavenDependencyAbstractCause cause) {
        omittedPipelineTriggerCauses.add(new OtherMavenDependencyCause(cause.getShortDescription()));
    }

    private void createTriggerCause(TaskListener listener, List<Cause> omittedPipelineTriggerCauses, MavenDependencyAbstractCause cause) {
        try {
            MavenDependencyCause mavenDependencyCause = cause.clone();
            mavenDependencyCause.setOmittedPipelineFullNames(Collections.emptyList());
            omittedPipelineTriggerCauses.add((Cause) mavenDependencyCause);
        } catch (CloneNotSupportedException e) {
            logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Failure to clone pipeline cause {}: {}", cause, e);
            omittedPipelineTriggerCauses.add(new OtherMavenDependencyCause(cause.getShortDescription()));
        }
    }

    private void createTriggerCause(WorkflowRun upstreamBuild, List<Cause> omittedPipelineTriggerCauses, MavenDependencyUpstreamCause cause) {
        Run<?, ?> upstreamRun = cause.getUpstreamRun() == null ? upstreamBuild : cause.getUpstreamRun();
        omittedPipelineTriggerCauses.add(new MavenDependencyUpstreamCause(upstreamRun, cause.getMavenArtifacts(), Collections.emptyList()));
    }

    private Map<String, List<MavenDependencyCause>> findOmittedDownstreamPipelines(WorkflowRun upstreamBuild) {
        Map<String, List<MavenDependencyCause>> omittedPipelineFullNamesAndCauses = new HashMap<>();
        for (Cause cause : upstreamBuild.getCauses()) {
            if (cause instanceof MavenDependencyCause) {
                MavenDependencyCause mavenDependencyCause = (MavenDependencyCause) cause;
                for (String omittedPipelineFullName : mavenDependencyCause.getOmittedPipelineFullNames()) {
                    omittedPipelineFullNamesAndCauses.computeIfAbsent(omittedPipelineFullName, p -> new ArrayList<>()).add(mavenDependencyCause);
                }
            }
        }

        return omittedPipelineFullNamesAndCauses;
    }

    private boolean shouldSkipTriggeringDownstreamBuilds(WorkflowRun upstreamBuild) {
        return !globalPipelineMavenConfig.getTriggerDownstreamBuildsResultsCriteria().contains(upstreamBuild.getResult());
    }

    private Pair<Map<String, Set<MavenArtifact>>, Map<String, Set<String>>> collectPipelinesToTrigger(WorkflowRun upstreamBuild, TaskListener listener) {
        WorkflowJob upstreamPipeline = upstreamBuild.getParent();

        String upstreamPipelineFullName = upstreamPipeline.getFullName();
        int upstreamBuildNumber = upstreamBuild.getNumber();
        Map<MavenArtifact, SortedSet<String>> downstreamPipelinesByArtifact = globalPipelineMavenConfig.getDao().listDownstreamJobsByArtifact(upstreamPipelineFullName, upstreamBuildNumber);
        logDebugMessage("got downstreamPipelinesByArtifact for project {} and build #{}: {}", upstreamPipelineFullName, upstreamBuildNumber, downstreamPipelinesByArtifact);

        Map<String, Set<MavenArtifact>> jobsToTrigger = new TreeMap<>();
        Map<String, Set<String>> omittedPipelineTriggersByPipelineFullname = new HashMap<>();

        // build the list of pipelines to trigger
        for (Map.Entry<MavenArtifact, SortedSet<String>> entry : downstreamPipelinesByArtifact.entrySet()) {

            MavenArtifact mavenArtifact = entry.getKey();
            SortedSet<String> downstreamPipelines = entry.getValue();

            for (String downstreamPipelineFullName : downstreamPipelines) {

                if (jobsToTrigger.containsKey(downstreamPipelineFullName)) {
                    // downstream pipeline has already been added to the list of pipelines to trigger,
                    // we have already verified that it's meeting requirements (not an infinite loop, authorized by security, not excessive triggering, buildable...)
                    logDebugMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip eligibility check of pipeline {} for artifact {}, eligibility already confirmed", downstreamPipelineFullName, mavenArtifact.getShortDescription());
                    Set<MavenArtifact> mavenArtifacts = jobsToTrigger.get(downstreamPipelineFullName);
                    if (mavenArtifacts == null) {
                        logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Invalid state, no artifacts found for pipeline '{}' while evaluating {}", downstreamPipelineFullName, mavenArtifact.getShortDescription());
                    } else {
                        mavenArtifacts.add(mavenArtifact);
                    }

                    continue;
                }

                if (isSamePipeline(upstreamPipelineFullName, downstreamPipelineFullName)) {
                    continue;
                }

                final WorkflowJob downstreamPipeline = Jenkins.get().getItemByFullName(downstreamPipelineFullName, WorkflowJob.class);
                if (doesNotExists(upstreamBuild, downstreamPipelineFullName, downstreamPipeline)) {
                    continue;
                }

                int downstreamBuildNumber = downstreamPipeline.getLastBuild().getNumber();

                if (generatesSameArtifact(listener, mavenArtifact, downstreamPipelineFullName, downstreamPipeline, downstreamBuildNumber)) {
                    continue;
                }

                if (isInfiniteLoop(listener, upstreamPipeline, upstreamPipelineFullName, mavenArtifact, downstreamPipelineFullName, downstreamPipeline, downstreamBuildNumber)) {
                    continue;
                }

                if (isExcessiveTriggering(listener, upstreamPipeline, omittedPipelineTriggersByPipelineFullname, downstreamPipelines, downstreamPipelineFullName, downstreamPipeline, downstreamBuildNumber)) {
                    continue;
                }

                if (isNotBuildable(upstreamBuild, downstreamPipeline)) {
                    continue;
                }

                if (hasNoTrigger(upstreamBuild, downstreamPipeline)) {
                    continue;
                }

                addToJobsToTrigger(upstreamBuild, upstreamPipeline, upstreamPipelineFullName, jobsToTrigger, mavenArtifact, downstreamPipelineFullName, downstreamPipeline);
            }
        }

        return Pair.of(jobsToTrigger, omittedPipelineTriggersByPipelineFullname);
    }

    private void addToJobsToTrigger(WorkflowRun upstreamBuild, WorkflowJob upstreamPipeline, String upstreamPipelineFullName, Map<String, Set<MavenArtifact>> jobsToTrigger, MavenArtifact mavenArtifact, String downstreamPipelineFullName, WorkflowJob downstreamPipeline) {
        boolean downstreamVisibleByUpstreamBuildAuth = this.globalPipelineMavenConfig.getPipelineTriggerService().isDownstreamVisibleByUpstreamBuildAuth(downstreamPipeline);
        boolean upstreamVisibleByDownstreamBuildAuth = this.globalPipelineMavenConfig.getPipelineTriggerService().isUpstreamBuildVisibleByDownstreamBuildAuth(upstreamPipeline, downstreamPipeline);

        logDebugMessage("upstreamPipeline ({}, visibleByDownstreamBuildAuth: {}), downstreamPipeline ({}, visibleByUpstreamBuildAuth: {}), upstreamBuildAuth: {}",
                upstreamPipelineFullName,
                upstreamVisibleByDownstreamBuildAuth,
                downstreamPipeline.getFullName(),
                downstreamVisibleByUpstreamBuildAuth,
                Jenkins.getAuthentication2()
        );

        if (downstreamVisibleByUpstreamBuildAuth && upstreamVisibleByDownstreamBuildAuth) {
            Set<MavenArtifact> mavenArtifactsCausingTheTrigger = jobsToTrigger.computeIfAbsent(downstreamPipelineFullName, k -> new TreeSet<>());
            if (mavenArtifactsCausingTheTrigger.contains(mavenArtifact)) {
                // TODO display warning
            } else {
                mavenArtifactsCausingTheTrigger.add(mavenArtifact);
            }
        } else {
            logDebugMessage("Skip triggering of {} by {}: downstreamVisibleByUpstreamBuildAuth: {}, upstreamVisibleByDownstreamBuildAuth: {}",
                    downstreamPipeline.getFullName(),
                    upstreamBuild.getFullDisplayName(),
                    downstreamVisibleByUpstreamBuildAuth, upstreamVisibleByDownstreamBuildAuth
            );
        }
    }

    private boolean hasNoTrigger(WorkflowRun upstreamBuild, WorkflowJob downstreamPipeline) {
        WorkflowJobDependencyTrigger downstreamPipelineTrigger = this.globalPipelineMavenConfig.getPipelineTriggerService().getWorkflowJobDependencyTrigger(downstreamPipeline);
        if (downstreamPipelineTrigger == null) {
            logDebugMessage("Skip triggering of downstream pipeline {} from upstream build {}: dependency trigger not configured", downstreamPipeline.getFullName(), upstreamBuild.getFullDisplayName());
            return true;
        }
        return false;
    }

    private boolean isNotBuildable(WorkflowRun upstreamBuild, WorkflowJob downstreamPipeline) {
        if (!downstreamPipeline.isBuildable()) {
            logDebugMessage("Skip triggering of non buildable (disabled: {}, isHoldOffBuildUntilSave: {}) downstream pipeline {} from upstream build {}",
                    downstreamPipeline.isDisabled(),
                    downstreamPipeline.isHoldOffBuildUntilSave(),
                    downstreamPipeline.getFullName(),
                    upstreamBuild.getFullDisplayName()
            );

            return true;
        }
        return false;
    }

    private boolean isExcessiveTriggering(TaskListener listener, WorkflowJob upstreamPipeline, Map<String, Set<String>> omittedPipelineTriggersByPipelineFullname, SortedSet<String> downstreamPipelines, String downstreamPipelineFullName, WorkflowJob downstreamPipeline, int downstreamBuildNumber) {
        // Avoid excessive triggering
        // See #46313
        Map<String, Integer> transitiveUpstreamPipelines = globalPipelineMavenConfig.getDao().listTransitiveUpstreamJobs(downstreamPipelineFullName, downstreamBuildNumber);
        for (String transitiveUpstreamPipelineName : transitiveUpstreamPipelines.keySet()) {
            // Skip if one of the downstream's upstream is already building or in queue
            // Then it will get triggered anyway by that upstream, we don't need to trigger it again
            WorkflowJob transitiveUpstreamPipeline = Jenkins.get().getItemByFullName(transitiveUpstreamPipelineName, WorkflowJob.class);

            if (transitiveUpstreamPipeline == null) {
                // security: not allowed to view this transitive upstream pipeline, continue to loop
                continue;
            } else if (transitiveUpstreamPipeline.getFullName().equals(upstreamPipeline.getFullName())) {
                // this upstream pipeline of  the current downstreamPipeline is the upstream pipeline itself, continue to loop
                continue;
            } else if (transitiveUpstreamPipeline.isBuilding()) {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering {} because it has a dependency already building: {}",
                        ModelHyperlinkNote.encodeTo(downstreamPipeline),
                        ModelHyperlinkNote.encodeTo(transitiveUpstreamPipeline)
                );

                return true;
            } else if (transitiveUpstreamPipeline.isInQueue()) {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering {} because it has a dependency already building or in queue: {}",
                        ModelHyperlinkNote.encodeTo(downstreamPipeline),
                        ModelHyperlinkNote.encodeTo(transitiveUpstreamPipeline)
                );

                return true;
            } else if (downstreamPipelines.contains(transitiveUpstreamPipelineName)) {
                // Skip if this downstream pipeline will be triggered by another one of our downstream pipelines
                // That's the case when one of the downstream's transitive upstream is our own downstream
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering {} because it has a dependency on a pipeline that will be triggered by this build: {}",
                        ModelHyperlinkNote.encodeTo(downstreamPipeline),
                        ModelHyperlinkNote.encodeTo(transitiveUpstreamPipeline)
                );

                omittedPipelineTriggersByPipelineFullname.computeIfAbsent(transitiveUpstreamPipelineName, p -> new TreeSet<>()).add(downstreamPipelineFullName);
                return true;
            }
        }

        return false;
    }

    private boolean isInfiniteLoop(TaskListener listener, WorkflowJob upstreamPipeline, String upstreamPipelineFullName, MavenArtifact mavenArtifact, String downstreamPipelineFullName, WorkflowJob downstreamPipeline, int downstreamBuildNumber) {
        Map<MavenArtifact, SortedSet<String>> downstreamDownstreamPipelinesByArtifact = globalPipelineMavenConfig.getDao().listDownstreamJobsByArtifact(downstreamPipelineFullName, downstreamBuildNumber);
        for (Map.Entry<MavenArtifact, SortedSet<String>> entry2 : downstreamDownstreamPipelinesByArtifact.entrySet()) {
            SortedSet<String> downstreamDownstreamPipelines = entry2.getValue();
            if (downstreamDownstreamPipelines.contains(upstreamPipelineFullName)) {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Infinite loop detected: skip triggering {} (dependency: {}) because it is itself triggering this pipeline (dependency: {})",
                        ModelHyperlinkNote.encodeTo(downstreamPipeline),
                        mavenArtifact.getShortDescription(),
                        ModelHyperlinkNote.encodeTo(upstreamPipeline),
                        entry2.getKey().getShortDescription()
                );
                // prevent infinite loop
                return true;
            }
        }
        return false;
    }

    private boolean generatesSameArtifact(TaskListener listener, MavenArtifact mavenArtifact, String downstreamPipelineFullName, WorkflowJob downstreamPipeline, int downstreamBuildNumber) {
        List<MavenArtifact> downstreamPipelineGeneratedArtifacts = globalPipelineMavenConfig.getDao().getGeneratedArtifacts(downstreamPipelineFullName, downstreamBuildNumber);
        logDebugMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Pipeline {} evaluated for because it has a dependency on {} generates {}",
                ModelHyperlinkNote.encodeTo(downstreamPipeline),
                mavenArtifact,
                downstreamPipelineGeneratedArtifacts
        );

        for (MavenArtifact downstreamPipelineGeneratedArtifact : downstreamPipelineGeneratedArtifacts) {
            if (Objects.equals(mavenArtifact.getGroupId(), downstreamPipelineGeneratedArtifact.getGroupId()) &&
                    Objects.equals(mavenArtifact.getArtifactId(), downstreamPipelineGeneratedArtifact.getArtifactId())) {
                logDebugMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering {} for {} because it generates artifact with same groupId:artifactId {}",
                        ModelHyperlinkNote.encodeTo(downstreamPipeline),
                        mavenArtifact,
                        downstreamPipelineGeneratedArtifact
                );

                return true;
            }
        }
        return false;
    }

    private boolean doesNotExists(WorkflowRun upstreamBuild, String downstreamPipelineFullName, WorkflowJob downstreamPipeline) {
        if (downstreamPipeline == null || downstreamPipeline.getLastBuild() == null) {
            logDebugMessage("Downstream pipeline {} or downstream pipeline last build not found from upstream build {}. Database synchronization issue or security restriction?",
                    downstreamPipelineFullName,
                    upstreamBuild.getFullDisplayName(),
                    Jenkins.getAuthentication2()
            );

            return true;
        }
        return false;
    }

    private boolean isSamePipeline(String upstreamPipelineFullName, String downstreamPipelineFullName) {
        if (Objects.equals(downstreamPipelineFullName, upstreamPipelineFullName)) {
            // Don't trigger myself
            return true;
        }
        return false;
    }

    private void triggerPipelines(Map<String, Set<MavenArtifact>> jobsToTrigger, Map<String, Set<String>> omittedPipelineTriggersByPipelineFullname, WorkflowRun upstreamBuild, TaskListener listener) {
        // trigger the pipelines
        triggerPipelinesLoop:
        for (Map.Entry<String, Set<MavenArtifact>> entry : jobsToTrigger.entrySet()) {
            String downstreamJobFullName = entry.getKey();
            Job downstreamJob = Jenkins.get().getItemByFullName(downstreamJobFullName, Job.class);
            if (downstreamJob == null) {
                logDebugMessage("[withMaven] downstreamPipelineTriggerRunListener - Illegal state: {} not resolved", downstreamJobFullName);

                continue;
            }

            Set<MavenArtifact> mavenArtifacts = entry.getValue();

            // See jenkins.triggers.ReverseBuildTrigger.RunListenerImpl.onCompleted(Run, TaskListener)
            MavenDependencyUpstreamCause cause = new MavenDependencyUpstreamCause(upstreamBuild, mavenArtifacts, omittedPipelineTriggersByPipelineFullname.get(downstreamJobFullName));

            Run downstreamJobLastBuild = downstreamJob.getLastBuild();
            if (downstreamJobLastBuild == null) {
                // should never happen, we need at least one build to know the dependencies
                // trigger downstream pipeline anyway
            } else {
                List<MavenArtifact> matchingMavenDependencies = MavenDependencyCauseHelper.isSameCause(cause, downstreamJobLastBuild.getCauses());
                if (matchingMavenDependencies.isEmpty()) {
                    for (Map.Entry<String, Set<String>> omittedPipeline : omittedPipelineTriggersByPipelineFullname.entrySet()) {
                        if (omittedPipeline.getValue().contains(downstreamJobFullName)) {
                            Job transitiveDownstreamJob = Jenkins.get().getItemByFullName(entry.getKey(), Job.class);
                            logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering downstream pipeline {} because it will be triggered by transitive downstream {}",
                                    ModelHyperlinkNote.encodeTo(downstreamJob),
                                    ModelHyperlinkNote.encodeTo(transitiveDownstreamJob)
                            );

                            continue triggerPipelinesLoop; // don't trigger downstream pipeline
                        }
                    }
                    // trigger downstream pipeline
                } else {
                    downstreamJobLastBuild.addAction(new CauseAction(cause));
                    logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering downstream pipeline {} as it was already triggered for Maven dependencies: {}",
                            ModelHyperlinkNote.encodeTo(downstreamJob),
                            matchingMavenDependencies.stream().map(mavenDependency -> mavenDependency == null ? null : mavenDependency.getShortDescription()).collect(Collectors.joining(", "))
                    );

                    try {
                        downstreamJobLastBuild.save();
                    } catch (IOException e) {
                        logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Failure to update build {}: {}",
                                downstreamJobLastBuild.getFullDisplayName(),
                                e.toString()
                        );
                    }
                    continue; // don't trigger downstream pipeline
                }
            }

            Queue.Item queuedItem = ParameterizedJobMixIn.scheduleBuild2(downstreamJob, -1, new CauseAction(cause));

            String dependenciesMessage = cause.getMavenArtifactsDescription();
            if (queuedItem == null) {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Skip triggering downstream pipeline {} due to dependencies on {}, invocation rejected.",
                        ModelHyperlinkNote.encodeTo(downstreamJob),
                        dependenciesMessage
                );
            } else {
                logInfoMessage(listener, "[withMaven] downstreamPipelineTriggerRunListener - Triggering downstream pipeline {}#{} due to dependency on {} ...",
                        ModelHyperlinkNote.encodeTo(downstreamJob),
                        downstreamJob.getNextBuildNumber(),
                        dependenciesMessage
                );
            }

        }
    }

    private void logDebugMessage(String pattern, Object... arguments) {
        LOGGER.debug(pattern, arguments);
    }

    private void logDebugMessage(TaskListener listener, String pattern, Object... arguments) {
        if (isDebugLogEnabled()) {
            listener.getLogger().println(String.format(pattern, arguments));
        }
    }

    private void logInfoMessage(TaskListener listener, String pattern, Object... arguments) {
        listener.getLogger().println(String.format(pattern, arguments));
    }

    private boolean isDebugLogEnabled() {
        return LOGGER.isDebugEnabled();
    }

}
