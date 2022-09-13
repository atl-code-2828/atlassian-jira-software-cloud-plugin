package com.atlassian.jira.cloud.jenkins.deploymentinfo.service;

import com.atlassian.jira.cloud.jenkins.common.client.ApiUpdateFailedException;
import com.atlassian.jira.cloud.jenkins.common.config.JiraSiteConfigRetriever;
import com.atlassian.jira.cloud.jenkins.common.response.JiraCommonResponse;
import com.atlassian.jira.cloud.jenkins.common.response.JiraSendInfoResponse;
import com.atlassian.jira.cloud.jenkins.common.service.IssueKeyExtractor;
import com.atlassian.jira.cloud.jenkins.config.JiraCloudSiteConfig;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.DeploymentPayloadBuilder;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.DeploymentsApi;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.Association;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.AssociationType;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.Command;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.DeploymentApiResponse;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.Deployments;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.Environment;
import com.atlassian.jira.cloud.jenkins.logging.PipelineLogger;
import com.atlassian.jira.cloud.jenkins.tenantinfo.CloudIdResolver;
import com.atlassian.jira.cloud.jenkins.util.JenkinsToJiraStatus;
import com.atlassian.jira.cloud.jenkins.util.RunWrapperProvider;
import com.atlassian.jira.cloud.jenkins.util.SecretRetriever;
import com.atlassian.jira.cloud.jenkins.util.StateValidator;
import com.google.common.collect.ImmutableList;
import hudson.model.Result;
import hudson.model.Run;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of JiraDeploymentInfoSender to send build updates to Jira by building the payload,
 * generating the access token, sending the request and parsing the response.
 */
public class JiraDeploymentInfoSenderImpl implements JiraDeploymentInfoSender {

    private static final String HTTPS_PROTOCOL = "https://";

    // this code do the same thing as RunWrapper#getCurrentResult()
    private static final Function<WorkflowRun, String> getJenkinsBuildStatus =
            run ->
                    Optional.ofNullable(run.getResult())
                            .map(Result::toString)
                            .orElseGet(Result.SUCCESS::toString);

    private final JiraSiteConfigRetriever siteConfigRetriever;
    private final SecretRetriever secretRetriever;
    private final CloudIdResolver cloudIdResolver;
    private final DeploymentsApi deploymentsApi;
    private final RunWrapperProvider runWrapperProvider;
    private final IssueKeyExtractor issueKeyExtractor;

    public JiraDeploymentInfoSenderImpl(
            final JiraSiteConfigRetriever siteConfigRetriever,
            final SecretRetriever secretRetriever,
            final CloudIdResolver cloudIdResolver,
            final DeploymentsApi jiraApi,
            final IssueKeyExtractor issueKeyExtractor,
            final RunWrapperProvider runWrapperProvider) {
        this.siteConfigRetriever = requireNonNull(siteConfigRetriever);
        this.secretRetriever = requireNonNull(secretRetriever);
        this.cloudIdResolver = requireNonNull(cloudIdResolver);
        this.deploymentsApi = requireNonNull(jiraApi);
        this.runWrapperProvider = requireNonNull(runWrapperProvider);
        this.issueKeyExtractor = requireNonNull(issueKeyExtractor);
    }

    @Override
    public List<JiraSendInfoResponse> sendDeploymentInfo(
            final JiraDeploymentInfoRequest request, final PipelineLogger pipelineLogger) {
        final List<JiraSendInfoResponse> responses = new LinkedList<>();
        if (request.getSite() == null) {
            List<String> jiraSites = siteConfigRetriever.getAllJiraSites();
            if (jiraSites.size() >= 2 && request.getEnableGating()) {
                responses.add(JiraDeploymentInfoResponse.failureGatingManyJiras());
            } else {
                for (final String jiraSite : jiraSites) {
                    final Optional<JiraCloudSiteConfig> maybeSiteConfig =
                            getSiteConfigFor(jiraSite);

                    responses.add(
                            maybeSiteConfig
                                    .map(
                                            siteConfig ->
                                                    sendDeploymentInfoToJiraSite(
                                                            siteConfig, request, pipelineLogger))
                                    .orElse(
                                            JiraCommonResponse.failureSiteConfigNotFound(
                                                    jiraSite)));
                }
            }
        } else {
            final Optional<JiraCloudSiteConfig> maybeSiteConfig =
                    getSiteConfigFor(request.getSite());
            responses.add(
                    maybeSiteConfig
                            .map(
                                    siteConfig ->
                                            sendDeploymentInfoToJiraSite(
                                                    siteConfig, request, pipelineLogger))
                            .orElse(
                                    JiraCommonResponse.failureSiteConfigNotFound(
                                            request.getSite())));
        }
        return responses;
    }

    /**
     * Sends deployment data to a Jira site.
     *
     * @param siteConfig - Jira to send data to
     * @param request - JiraBuildInfoRequest::site is ignored and jiraSite is used instead
     */
    private JiraSendInfoResponse sendDeploymentInfoToJiraSite(
            @Nonnull final JiraCloudSiteConfig siteConfig,
            final JiraDeploymentInfoRequest request,
            final PipelineLogger pipelineLogger) {
        final WorkflowRun deployment = request.getDeployment();
        final Set<String> serviceIds = request.getServiceIds();
        final boolean enableGating = request.getEnableGating();
        final Set<String> requestIssueKeys = request.getIssueKeys();

        final String jiraSite = siteConfig.getSite();

        final Optional<String> maybeSecret = getSecretFor(siteConfig.getCredentialsId());

        if (!maybeSecret.isPresent()) {
            return JiraCommonResponse.failureSecretNotFound(jiraSite);
        }

        final Environment environment = buildEnvironment(request);
        List<String> errorMessages = EnvironmentValidator.validate(environment);

        if (!errorMessages.isEmpty()) {
            return JiraDeploymentInfoResponse.failureEnvironmentInvalid(jiraSite, errorMessages);
        }

        final String deploymentState = getDeploymentState(deployment, request.getState());
        errorMessages = StateValidator.validate(deploymentState);

        if (!errorMessages.isEmpty()) {
            return JiraDeploymentInfoResponse.failureStateInvalid(jiraSite, errorMessages);
        }

        final Set<String> issueKeys;

        if (requestIssueKeys.isEmpty()) {
            issueKeys = issueKeyExtractor.extractIssueKeys(deployment, pipelineLogger);
        } else {
            issueKeys = requestIssueKeys;
        }

        if (issueKeys.isEmpty() && serviceIds.isEmpty()) {
            return JiraDeploymentInfoResponse.skippedIssueKeysNotFoundAndServiceIdsAreEmpty(
                    jiraSite);
        }

        final Set<Association> associations = buildAssociations(issueKeys, serviceIds);

        final Optional<String> maybeCloudId = getCloudIdFor(jiraSite);

        if (!maybeCloudId.isPresent()) {
            return JiraCommonResponse.failureSiteNotFound(jiraSite);
        }

        final List<Command> commands = buildCommands(enableGating);

        final Deployments deploymentInfo =
                createJiraDeploymentInfo(
                        deployment, environment, associations, deploymentState, commands);

        try {
            return handleDeploymentApiResponse(
                    jiraSite,
                    deploymentsApi.sendDeploymentAsJwt(
                            siteConfig.getWebhookUrl(),
                            deploymentInfo,
                            maybeSecret.get(),
                            pipelineLogger));
        } catch (ApiUpdateFailedException e) {
            return handleDeploymentApiError(jiraSite, e.getMessage());
        }
    }

    private Optional<JiraCloudSiteConfig> getSiteConfigFor(@Nullable final String jiraSite) {
        return siteConfigRetriever.getJiraSiteConfig(jiraSite);
    }

    private Optional<String> getCloudIdFor(final String jiraSite) {
        final String jiraSiteUrl = HTTPS_PROTOCOL + jiraSite;
        return cloudIdResolver.getCloudId(jiraSiteUrl);
    }

    private Optional<String> getSecretFor(final String credentialsId) {
        return secretRetriever.getSecretFor(credentialsId);
    }

    private Deployments createJiraDeploymentInfo(
            final Run build,
            final Environment environment,
            final Set<Association> associations,
            final String state,
            final List<Command> commands) {
        final RunWrapper buildWrapper = runWrapperProvider.getWrapper(build);

        return DeploymentPayloadBuilder.getDeploymentInfo(
                buildWrapper, environment, associations, state, commands);
    }

    private JiraSendInfoResponse handleDeploymentApiResponse(
            final String jiraSite, final DeploymentApiResponse response) {
        if (!response.getAcceptedDeployments().isEmpty()) {
            return JiraDeploymentInfoResponse.successDeploymentAccepted(jiraSite, response);
        }

        if (!response.getUnknownAssociations().isEmpty()) {
            return JiraDeploymentInfoResponse.failureUnknownAssociations(jiraSite, response);
        }

        if (!response.getRejectedDeployments().isEmpty()) {
            return JiraDeploymentInfoResponse.failureDeploymentRejected(jiraSite, response);
        }

        return JiraDeploymentInfoResponse.failureUnexpectedResponse(jiraSite);
    }

    private JiraDeploymentInfoResponse handleDeploymentApiError(
            final String jiraSite, final String errorMessage) {
        return JiraDeploymentInfoResponse.failureDeploymentsApiResponse(jiraSite, errorMessage);
    }

    private Environment buildEnvironment(final JiraDeploymentInfoRequest request) {
        // JENKINS-59862: if environmentType parameter was not provided, we should fallback to
        // "unmapped"
        final String environmentType =
                StringUtils.isNotBlank(request.getEnvironmentType())
                        ? request.getEnvironmentType()
                        : "unmapped";
        return Environment.builder()
                .withId(request.getEnvironmentId())
                .withDisplayName(request.getEnvironmentName())
                .withType(environmentType)
                .build();
    }

    /**
     * Gets deployment state, in case if user didn't pass state explicitly it will be extracted from
     * Jenkins build
     */
    private String getDeploymentState(final WorkflowRun build, @Nullable final String state) {
        return Optional.ofNullable(state)
                .orElseGet(() -> JenkinsToJiraStatus.getState(build.getResult()).value);
    }

    private Set<Association> buildAssociations(
            final Set<String> issueKeys, final Set<String> serviceIds) {
        final HashSet<Association> associations = new HashSet<>();

        if (!issueKeys.isEmpty()) {
            associations.add(
                    Association.builder()
                            .withAssociationType(AssociationType.ISSUE_KEYS)
                            .withValues(issueKeys)
                            .build());
        }

        if (!serviceIds.isEmpty()) {
            associations.add(
                    Association.builder()
                            .withAssociationType(AssociationType.SERVICE_ID_OR_KEYS)
                            .withValues(serviceIds)
                            .build());
        }
        return associations;
    }

    private List<Command> buildCommands(@Nullable final Boolean enableGating) {
        final ImmutableList.Builder<Command> commandsBuilder = ImmutableList.builder();
        if (enableGating != null && enableGating) {
            commandsBuilder.add(new Command("initiate_deployment_gating"));
        }
        return commandsBuilder.build();
    }
}
