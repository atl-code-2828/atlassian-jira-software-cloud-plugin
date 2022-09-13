package com.atlassian.jira.cloud.jenkins.checkgatingstatus.service;

import com.atlassian.jira.cloud.jenkins.checkgatingstatus.client.GatingStatusApi;
import com.atlassian.jira.cloud.jenkins.checkgatingstatus.client.model.GatingStatusResponse;
import com.atlassian.jira.cloud.jenkins.common.config.JiraSiteConfigRetriever;
import com.atlassian.jira.cloud.jenkins.common.response.JiraCommonResponse;
import com.atlassian.jira.cloud.jenkins.config.JiraCloudSiteConfig;
import com.atlassian.jira.cloud.jenkins.logging.PipelineLogger;
import com.atlassian.jira.cloud.jenkins.tenantinfo.CloudIdResolver;
import com.atlassian.jira.cloud.jenkins.util.SecretRetriever;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class JiraGatingStatusRetrieverImpl implements JiraGatingStatusRetriever {

    private static final String HTTPS_PROTOCOL = "https://";

    private final JiraSiteConfigRetriever siteConfigRetriever;
    private final SecretRetriever secretRetriever;
    private final CloudIdResolver cloudIdResolver;
    private final GatingStatusApi gatingApi;

    private static final Logger logger =
            LoggerFactory.getLogger(JiraGatingStatusRetrieverImpl.class);

    public JiraGatingStatusRetrieverImpl(
            final JiraSiteConfigRetriever siteConfigRetriever,
            final SecretRetriever secretRetriever,
            final CloudIdResolver cloudIdResolver,
            final GatingStatusApi gatingApi) {
        this.siteConfigRetriever = siteConfigRetriever;
        this.secretRetriever = secretRetriever;
        this.cloudIdResolver = cloudIdResolver;
        this.gatingApi = gatingApi;
    }

    @Override
    public JiraGatingStatusResponse getGatingStatus(
            final TaskListener taskListener,
            final String jiraSite,
            final String environmentId,
            final WorkflowRun run) {

        final Optional<JiraCloudSiteConfig> maybeSiteConfig =
                siteConfigRetriever.getJiraSiteConfig(jiraSite);

        if (!maybeSiteConfig.isPresent()) {
            return JiraGatingStatusResponse.of(
                    JiraCommonResponse.failureSiteConfigNotFound(jiraSite));
        }

        final String resolvedSiteConfig = maybeSiteConfig.get().getSite();

        final JiraCloudSiteConfig siteConfig = maybeSiteConfig.get();
        final Optional<String> maybeSecret =
                secretRetriever.getSecretFor(siteConfig.getCredentialsId());

        if (!maybeSecret.isPresent()) {
            return JiraGatingStatusResponse.of(
                    JiraCommonResponse.failureSecretNotFound(resolvedSiteConfig));
        }

        final Optional<String> maybeCloudId =
                cloudIdResolver.getCloudId(HTTPS_PROTOCOL + resolvedSiteConfig);

        if (!maybeCloudId.isPresent()) {
            return JiraGatingStatusResponse.of(
                    JiraCommonResponse.failureSiteNotFound(resolvedSiteConfig));
        }

        String deploymentId = Integer.toString(run.getNumber());
        String pipelineId = String.valueOf(run.getParent().getFullName().hashCode());

        try {
            final GatingStatusResponse result =
                    gatingApi.getGatingStatus(
                            siteConfig.getWebhookUrl(),
                            maybeSecret.get(),
                            deploymentId,
                            pipelineId,
                            environmentId,
                            PipelineLogger.noopInstance());

            return JiraGatingStatusResponse.success(jiraSite, result);
        } catch (Exception e) {
            String message =
                    String.format(
                            "Error while retrieving gating status for jira site '%s', deployment ID '%s', pipelineId '%s', and environmentId '%s'",
                            jiraSite, deploymentId, pipelineId, environmentId);
            logger.error(message, e);
            taskListener.error(message);
            final String errorMessage = e.getMessage();
            return JiraGatingStatusResponse.failure(jiraSite, errorMessage);
        }
    }
}
