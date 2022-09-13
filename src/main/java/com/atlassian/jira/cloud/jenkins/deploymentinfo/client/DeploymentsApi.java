package com.atlassian.jira.cloud.jenkins.deploymentinfo.client;

import com.atlassian.jira.cloud.jenkins.common.client.ApiUpdateFailedException;
import com.atlassian.jira.cloud.jenkins.common.client.JenkinsAppApi;
import com.atlassian.jira.cloud.jenkins.common.client.JenkinsAppEventRequest;
import com.atlassian.jira.cloud.jenkins.common.client.JenkinsAppRequest;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.DeploymentApiResponse;
import com.atlassian.jira.cloud.jenkins.deploymentinfo.client.model.Deployments;
import com.atlassian.jira.cloud.jenkins.logging.PipelineLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;

public class DeploymentsApi extends JenkinsAppApi<DeploymentApiResponse> {

    public DeploymentsApi(final OkHttpClient httpClient, final ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    public DeploymentApiResponse sendDeploymentAsJwt(
            final String webhookUrl,
            final Deployments deploymentsRequest,
            final String secret,
            final PipelineLogger pipelineLogger)
            throws ApiUpdateFailedException {

        JenkinsAppRequest request = createRequest(deploymentsRequest);
        return this.sendRequestAsJwt(
                webhookUrl, secret, request, DeploymentApiResponse.class, pipelineLogger);
    }

    @NotNull
    private JenkinsAppRequest createRequest(final Deployments deploymentsRequest) {
        return new JenkinsAppEventRequest(
                JenkinsAppEventRequest.EventType.DEPLOYMENT,
                deploymentsRequest.getDeployment().getPipeline().getId(),
                deploymentsRequest.getDeployment().getDisplayName(),
                deploymentsRequest.getDeployment().getState(),
                deploymentsRequest.getDeployment().getLastUpdated(),
                deploymentsRequest);
    }
}
