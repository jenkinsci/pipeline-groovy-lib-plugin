/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.libs;

import hudson.model.Result;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.junit.jupiter.WithGitSampleRepo;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WithGitSampleRepo
class GlobalUntrustedLibrariesTest {

    private JenkinsRule r;
    private GitSampleRepoRule sampleRepo;
    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @BeforeEach
    void beforeEach(JenkinsRule rule, GitSampleRepoRule repo) {
        r = rule;
        sampleRepo = repo;
    }

    @Test
    void configRoundtrip() throws Exception {
        r.configRoundtrip();
        GlobalLibrariesTest.configRoundtrip(r, GlobalUntrustedLibraries.get(), Jenkins.READ, Jenkins.MANAGE);
    }

    @Issue("SECURITY-1422")
    @Test
    void checkDefaultVersionRestricted() throws Exception {
        GlobalLibrariesTest.checkDefaultVersionRestricted(r, sampleRepo, GlobalUntrustedLibraries.get());
    }

    @Test
    void noGrape() throws Exception {
        GlobalUntrustedLibraries.get().setLibraries(List.of(LibraryTestUtils.defineLibraryUsingGrab("grape", sampleRepo)));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('grape@master') import pkg.Wrapper; echo(/should not have been able to run ${pkg.Wrapper.list()}/)", true));
        r.assertLogContains("Annotation Grab cannot be used in the sandbox", r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
    }
}
