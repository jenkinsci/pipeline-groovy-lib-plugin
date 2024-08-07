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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import hudson.security.Permission;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

public class GlobalLibrariesTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();
    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Test public void configRoundtrip() throws Exception {
        r.configRoundtrip();
        configRoundtrip(r, GlobalLibraries.get(), Jenkins.ADMINISTER);
    }

    @Issue("SECURITY-1422")
    @Test public void checkDefaultVersionRestricted() throws Exception {
        checkDefaultVersionRestricted(r, sampleRepo, GlobalLibraries.get());
    }

    @Test public void allowedGrape() throws Exception {
        GlobalLibraries.get().setLibraries(List.of(LibraryTestUtils.defineLibraryUsingGrab("grape", sampleRepo)));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('grape@master') import pkg.Wrapper; echo(/should be able to run ${pkg.Wrapper.list()}/)", true));
        r.assertBuildStatusSuccess(p.scheduleBuild2(0));
    }

    static void checkDefaultVersionRestricted(JenkinsRule r, GitSampleRepoRule sampleRepo, AbstractGlobalLibraries gl) throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        MockAuthorizationStrategy s = new MockAuthorizationStrategy()
            .grant(Jenkins.READ).everywhere().toEveryone()
            .grant(Jenkins.ADMINISTER).everywhere().to("admin");
        r.jenkins.setAuthorizationStrategy(s);
        LibraryConfiguration foo = new LibraryConfiguration("foo", new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString())));
        gl.setLibraries(Arrays.asList(foo));
        JenkinsRule.WebClient wc = r.createWebClient();
        wc.setThrowExceptionOnFailingStatusCode(false);
        WebRequest req = new WebRequest(new URL(wc.getContextPath() + "/descriptorByName/" +
                LibraryConfiguration.class.getName() + "/checkDefaultVersion"), HttpMethod.POST);
        req.setRequestParameters(Arrays.asList(
                new NameValuePair("name", "foo"),
                new NameValuePair("defaultVersion", "master"),
                new NameValuePair("value", "master"),
                new NameValuePair("implicit", "false"),
                new NameValuePair("allowVersionOverride", "true")));
        wc.addCrumb(req);
        wc.login("user", "user");
        assertThat(wc.getPage(req).getWebResponse().getContentAsString(),
                containsString("Cannot validate default version until after saving and reconfiguring"));
        wc.login("admin", "admin");
        assertThat(wc.getPage(req).getWebResponse().getContentAsString(),
                containsString("Currently maps to revision"));
    }

    static void configRoundtrip(JenkinsRule r, AbstractGlobalLibraries gl, Permission... alicePrivileges) throws Exception {
        assertEquals(Collections.emptyList(), gl.getLibraries());
        LibraryConfiguration bar = new LibraryConfiguration("bar", new SCMSourceRetriever(new GitSCMSource(null, "https://phony.jenkins.io/bar.git", "", "origin", "+refs/heads/*:refs/remotes/origin/*", "*", "", true)));
        LibraryCachingConfiguration cachingConfiguration = new LibraryCachingConfiguration(120, "develop", "master stable");
        bar.setCachingConfiguration(cachingConfiguration);
        bar.setDefaultVersion("master");
        bar.setImplicit(true);
        bar.setAllowVersionOverride(false);
        gl.setLibraries(Arrays.asList(bar));
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
                grant(alicePrivileges).everywhere().to("alice")
        );
        HtmlPage configurePage = r.createWebClient().login("alice").goTo("configure");
        assertThat(configurePage.getWebResponse().getContentAsString(), containsString("https://phony.jenkins.io/bar.git"));
        r.submit(configurePage.getFormByName("config")); // JenkinsRule.configRoundtrip expanded to include login
        List<LibraryConfiguration> libs = gl.getLibraries();
        r.assertEqualDataBoundBeans(Arrays.asList(bar), libs);
        libs = gl.getLibraries();
        r.assertEqualDataBoundBeans(Arrays.asList(bar), libs);
        boolean noFoo = true;
        for (LibraryConfiguration lib : libs) {
            if ("foo".equals(lib.getName())) {
                noFoo = false;
                r.assertEqualDataBoundBeans(lib.getCachingConfiguration(), cachingConfiguration);
            }
        }
        assertFalse("Missing a library called foo (should not happen)", noFoo);
    }
}
