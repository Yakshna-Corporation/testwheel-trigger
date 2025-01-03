package org.yakshna.testwheel.apiplugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;

public class TestWheelTrigger extends Builder implements SimpleBuildStep {

	private final String apiKey;
	private final String prjctKey;
	
	private String url = "https://app.testwheel.com/test-appln";
	
	static final String STATUS = "status";

	@DataBoundConstructor
	public TestWheelTrigger(String apiKey, String prjctKey) {
		this.apiKey = apiKey;
		this.prjctKey = prjctKey;
	}

	public String getApiKey() {
		return apiKey;
	}
	
	public String getPrjctKey() {
		return prjctKey;
	}

	@SuppressWarnings("deprecation")
	@SuppressFBWarnings("REC_CATCH_EXCEPTION")
	@Override
	public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener) {
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			url = url + "?apiKey=" + apiKey + "&prjctKey=" + prjctKey;
			HttpUriRequestBase request = new HttpGet(url);
			try (CloseableHttpResponse response = client.execute(request)) {
				if (response.getCode() == 201) {
					String responseBody = EntityUtils.toString(response.getEntity());
					JSONObject jsonResponse = new JSONObject(responseBody);
					if ("success".equalsIgnoreCase(jsonResponse.getString(STATUS))) {
						String runId = jsonResponse.getString("output");
						if (runId != null && !runId.isEmpty()) {
							String secondUrl = url + "&runId=" + runId;
							while (true) {
								HttpUriRequestBase secondRequest = new HttpGet(secondUrl);
								try (CloseableHttpResponse secondResponse = client.execute(secondRequest)) {
									String secondResponseBody = EntityUtils.toString(secondResponse.getEntity());
									JSONObject secondJsonResponse = new JSONObject(secondResponseBody);
									if ("SUCCESS".equalsIgnoreCase(secondJsonResponse.getString(STATUS))) {
										String reportUrl = secondJsonResponse.getString("output");
										listener.getLogger().println("Downloading report from: " + reportUrl);
										HttpUriRequestBase reportRequest = new HttpGet(reportUrl);
										try (CloseableHttpResponse reportResponse = client.execute(reportRequest);
												InputStream reportStream = reportResponse.getEntity().getContent()) {
											FilePath reportFilePath = workspace.child("report.pdf");
											try (OutputStream fos = reportFilePath.write()) {
												byte[] buffer = new byte[1024];
												int len;
												while ((len = reportStream.read(buffer)) != -1) {
													fos.write(buffer, 0, len);
												}
											}
											listener.getLogger().println(
													"Report downloaded successfully: " + reportFilePath.getRemote());
											run.setResult(Result.SUCCESS);
											return;
										}
									} else if ("FAILURE".equalsIgnoreCase(secondJsonResponse.getString(STATUS))) {
										listener.getLogger().println("API Test failed");
										run.setResult(Result.FAILURE);
										return;
									}
									Thread.sleep(20000); 
								} catch (ParseException e) {
									listener.getLogger().println("API Test failed");
									run.setResult(Result.FAILURE);
									return;
								}
							}
						} else {
							listener.getLogger().println("Output not found in the response");
							run.setResult(Result.FAILURE);
						}
					} else {
						listener.getLogger().println("API Request failed. Please Check the API URL");
						run.setResult(Result.FAILURE);
					}
				} else {
					listener.getLogger().println("API Request failed. Status: " + response.getCode());
					run.setResult(Result.FAILURE);
				}
			} catch (ParseException e1) {
				listener.getLogger().println("API Request failed. " + e1.getMessage());
				run.setResult(Result.FAILURE);
			}
		} catch (IOException e) {
			listener.getLogger().println("Error: " + e.getMessage());
			run.setResult(Result.FAILURE);
		} catch (InterruptedException e) {
            listener.getLogger().println("InterruptedException occurred: " + e.getMessage());
            run.setResult(Result.FAILURE);
            Thread.currentThread().interrupt(); 
        }
	}

	@Extension
	@Symbol("testwheelTrigger")
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "TestwheelTrigger";
		}
	}
}
