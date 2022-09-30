/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

import com.cloudbees.hudson.plugins.folder.Folder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.BranchSpec;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.slaves.WorkspaceList;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.impl.SingleSCMSource;
import jenkins.scm.impl.subversion.SubversionSCMSource;
import jenkins.scm.impl.subversion.SubversionSampleRepoRule;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.*;

import static hudson.ExtensionList.lookupSingleton;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.WithoutJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever.PROHIBITED_DOUBLE_DOT;
import static org.junit.Assume.assumeFalse;

public class SCMSourceRetrieverTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();
    @Rule public GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();
    @Rule public SubversionSampleRepoRule sampleRepoSvn = new SubversionSampleRepoRule();

    @Issue("JENKINS-40408")
    @Test public void lease() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("echoing",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('echoing@master') import myecho; myecho()", true));
        String checkoutDir = LibraryRecord.directoryNameFor("git " + sampleRepo.toString());
        FilePath base = r.jenkins.getWorkspaceFor(p).withSuffix("@libs").child(checkoutDir);
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun b = r.buildAndAssertSuccess(p);
            r.assertLogContains("something special", b);
            r.assertLogNotContains("Retrying after 10 seconds", b);
            assertFalse(base.child("vars").exists());
            assertFalse(base.withSuffix("-scm-key.txt").exists());
            assertTrue(base.withSuffix("@2").child("vars").exists());
            assertThat(base.withSuffix("@2-scm-key.txt").readToString(), equalTo("git " + sampleRepo.toString()));
        }
    }

    @Issue("JENKINS-41497")
    @Test public void includeChanges() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("include_changes",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('include_changes@master') import myecho; myecho()", true));
        FilePath base = r.jenkins.getWorkspaceFor(p).withSuffix("@libs").child("include_changes");
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun a = r.buildAndAssertSuccess(p);
            r.assertLogContains("something special", a);
        }
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something even more special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=library_commit");
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun b = r.buildAndAssertSuccess(p);
            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = b.getChangeSets();
            assertEquals(1, changeSets.size());
            ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = changeSets.get(0);
            assertEquals(b, changeSet.getRun());
            assertEquals("git", changeSet.getKind());
            Iterator<? extends ChangeLogSet.Entry> iterator = changeSet.iterator();
            ChangeLogSet.Entry entry = iterator.next();
            assertEquals("library_commit", entry.getMsg() );
            r.assertLogContains("something even more special", b);
            r.assertLogNotContains("Retrying after 10 seconds", b);
        }
    }

    @Issue("JENKINS-41497")
    @Test public void dontIncludeChanges() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration lc = new LibraryConfiguration("dont_include_changes", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        lc.setIncludeInChangesets(false);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('dont_include_changes@master') import myecho; myecho()", true));
        FilePath base = r.jenkins.getWorkspaceFor(p).withSuffix("@libs").child("dont_include_changes");
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun a = r.buildAndAssertSuccess(p);
        }
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something even more special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=library_commit");
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun b = r.buildAndAssertSuccess(p);
            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = b.getChangeSets();
            assertEquals(0, changeSets.size());
            r.assertLogNotContains("Retrying after 10 seconds", b);
        }
    }

    @Issue("JENKINS-38609")
    @Test public void libraryPath() throws Exception {
        sampleRepo.init();
        sampleRepo.write("sub/path/vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("root_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("sub/path/");
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('root_sub_path@master') import myecho; myecho()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("something special", b);
    }

    @Issue("JENKINS-38609")
    @Test public void libraryPathSecurity() throws Exception {
        sampleRepo.init();
        sampleRepo.write("sub/path/vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("root_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("sub/path/../../../jenkins_home/foo");
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('root_sub_path@master') import myecho; myecho()", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("Library path may not contain '..'", b);
    }

    @WithoutJenkins
    @Test public void libraryPathMatcher() {
        assertThat("..", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("./..", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("../foo", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("foo/../bar", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat(".\\..", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("..\\foo", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("foo\\..\\bar", matchesPattern(PROHIBITED_DOUBLE_DOT));
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_inline_staticStrings() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Basename "libname" notation => use specified default branch
        WorkflowJob p1 = r.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("@Library('branchylib') import myecho; myecho()", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@master", b1);
        r.assertLogContains("something special", b1);

        // Use specified branch
        WorkflowJob p2 = r.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition("@Library('branchylib@master') import myecho; myecho()", true));
        WorkflowRun b2 = r.buildAndAssertSuccess(p2);
        r.assertLogContains("Loading library branchylib@master", b2);
        r.assertLogContains("something special", b2);

        // Use another specified branch
        WorkflowJob p3 = r.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition("@Library('branchylib@feature') import myecho; myecho()", true));
        WorkflowRun b3 = r.buildAndAssertSuccess(p3);
        r.assertLogContains("Loading library branchylib@feature", b3);
        r.assertLogContains("something very special", b3);

        // Use a specified but missing branch
        WorkflowJob p4 = r.jenkins.createProject(WorkflowJob.class, "p4");
        p4.setDefinition(new CpsFlowDefinition("@Library('branchylib@bogus') import myecho; myecho()", true));
        WorkflowRun b4 = r.buildAndAssertStatus(Result.FAILURE, p4);
        r.assertLogContains("ERROR: No version bogus found for library branchylib", b4);
        r.assertLogContains("org.codehaus.groovy.control.MultipleCompilationErrorsException: startup failed:", b4);
        r.assertLogContains("WorkflowScript: Loading libraries failed", b4);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_inline_BRANCH_NAME() throws Exception {
        // Test that @Library('branchylib@${BRANCH_NAME}')
        // falls back to default for "Pipeline script" which
        // is not "from SCM", even when we try to confuse it
        // by having some checkouts (and so list of SCMs).

        // Do not let caller-provided BRANCH_NAME interfere here
        assumeFalse("An externally provided BRANCH_NAME envvar interferes with tested logic",
                System.getenv("BRANCH_NAME") != null);

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceBRANCH_NAME(true);

        sampleRepo2.init();
        sampleRepo2.write("vars/myecho2.groovy", "def call() {echo 'something weird'}");
        sampleRepo2.git("add", "vars");
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("checkout", "-b", "feature");
        sampleRepo2.write("vars/myecho2.groovy", "def call() {echo 'something wonderful'}");
        sampleRepo2.git("add", "vars");
        sampleRepo2.git("commit", "--message=init");
        SCMSourceRetriever scm2 = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo2.toString(), "", "*", "", true));
        LibraryConfiguration lc2 = new LibraryConfiguration("branchylib2", scm2);
        lc2.setDefaultVersion("master");
        lc2.setIncludeInChangesets(false);
        lc2.setAllowVersionOverride(true);
        lc2.setAllowBRANCH_NAME(true);
        lc2.setTraceBRANCH_NAME(true);

        // Configure two libs to make a mess :)
        GlobalLibraries.get().setLibraries(Arrays.asList(lc, lc2));

        // Branch context for job not set - fall back to default
        WorkflowJob p0 = r.jenkins.createProject(WorkflowJob.class, "p0");
        p0.setDefinition(new CpsFlowDefinition("@Library('branchylib@${BRANCH_NAME}') import myecho; myecho()", true));
        WorkflowRun b0 = r.buildAndAssertSuccess(p0);
        r.assertLogContains("Loading library branchylib@master", b0);
        r.assertLogContains("something special", b0);

        // Branch context for second lib might be confused as "feature"
        // because the first loaded lib would become part of SCMs list
        // for this build, and there are no other SCMs in the list (an
        // inline pipeline). In fact the second lib should fall back to
        // "master" because the pipeline script is not from Git so there
        // is no "BRANCH_NAME" of its own.
        WorkflowJob p1 = r.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("@Library('branchylib@feature') import myecho; myecho(); @Library('branchylib2@${BRANCH_NAME}') import myecho2; myecho2()", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@feature", b1);
        r.assertLogContains("Loading library branchylib2@master", b1);
        r.assertLogContains("something very special", b1);
        r.assertLogContains("something weird", b1);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_MBP_BRANCH_NAME() throws Exception {
        // Create a MultiBranch Pipeline job instantiated from Git
        // and check behaviors with BRANCH_NAME="master",
        // BRANCH_NAME="feature", and BRANCH_NAME="bogus"
        // TODO? BRANCH_NAME=""
        // Note: An externally provided BRANCH_NAME envvar
        // does not interfere with tested logic, since MBP
        // sets the value for launched builds.

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@${BRANCH_NAME}') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("branch", "feature");
        sampleRepo2.git("branch", "bogus");

        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        BranchSource branchSource = new BranchSource(new GitSCMSource("source-id", sampleRepo2.toString(), "", "*", "", false));
        mbp.getSourcesList().add(branchSource);
        // Note: this notification causes discovery of branches,
        // definition of MBP "leaf" jobs, and launch of builds,
        // so below we just make sure they complete and analyze
        // the outcomes.
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());

        WorkflowJob p1 = mbp.getItem("master");
        WorkflowRun b1 = p1.getLastBuild();
        r.waitForCompletion(b1);
        assertFalse(p1.isBuilding());
        r.assertBuildStatusSuccess(b1);
        r.assertLogContains("Loading library branchylib@master", b1);
        r.assertLogContains("something special", b1);

        WorkflowJob p2 = mbp.getItem("feature");
        WorkflowRun b2 = p2.getLastBuild();
        r.waitForCompletion(b2);
        assertFalse(p2.isBuilding());
        r.assertBuildStatusSuccess(b2);
        r.assertLogContains("Loading library branchylib@feature", b2);
        r.assertLogContains("something very special", b2);

        // library branch "bogus" does not exist => fall back to default (master)
        WorkflowJob p3 = mbp.getItem("bogus");
        WorkflowRun b3 = p3.getLastBuild();
        r.waitForCompletion(b3);
        assertFalse(p3.isBuilding());
        r.assertBuildStatusSuccess(b3);
        r.assertLogContains("Loading library branchylib@master", b3);
        r.assertLogContains("something special", b3);

        // TODO: test lc.setAllowBRANCH_NAME_PR(true) for PR builds
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_MBP_staticStrings() throws Exception {
        // Test that lc.setAllowBRANCH_NAME(false) does not
        // preclude fixed branch names (they should work),
        // like @Library('branchylib@master')

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@master') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=master");
        sampleRepo2.git("checkout", "-b", "feature");
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@feature') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=feature");
        sampleRepo2.git("checkout", "-b", "bogus");
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@bogus') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=bogus");

        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        BranchSource branchSource = new BranchSource(new GitSCMSource("source-id", sampleRepo2.toString(), "", "*", "", false));
        mbp.getSourcesList().add(branchSource);
        // Note: this notification causes discovery of branches,
        // definition of MBP "leaf" jobs, and launch of builds,
        // so below we just make sure they complete and analyze
        // the outcomes.
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());

        WorkflowJob p1 = mbp.getItem("master");
        WorkflowRun b1 = p1.getLastBuild();
        r.waitForCompletion(b1);
        assertFalse(p1.isBuilding());
        r.assertBuildStatusSuccess(b1);
        r.assertLogContains("Loading library branchylib@master", b1);
        r.assertLogContains("something special", b1);

        WorkflowJob p2 = mbp.getItem("feature");
        WorkflowRun b2 = p2.getLastBuild();
        r.waitForCompletion(b2);
        assertFalse(p2.isBuilding());
        r.assertBuildStatusSuccess(b2);
        r.assertLogContains("Loading library branchylib@feature", b2);
        r.assertLogContains("something very special", b2);

        WorkflowJob p3 = mbp.getItem("bogus");
        WorkflowRun b3 = p3.getLastBuild();
        r.waitForCompletion(b3);
        assertFalse(p3.isBuilding());
        r.assertBuildStatus(Result.FAILURE, b3);
        r.assertLogContains("ERROR: Could not resolve bogus", b3);
        r.assertLogContains("ambiguous argument 'bogus^{commit}': unknown revision or path not in the working tree", b3);
        r.assertLogContains("ERROR: No version bogus found for library branchylib", b3);
        r.assertLogContains("WorkflowScript: Loading libraries failed", b3);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_MBP_BRANCH_NAME_notAllowed() throws Exception {
        // Test that lc.setAllowBRANCH_NAME(false) causes
        // @Library('libname@${BRANCH_NAME}') to always fail
        // (not treated as a "version override" for funny
        // branch name that is literally "${BRANCH_NAME}").

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@${BRANCH_NAME}') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("branch", "feature");
        sampleRepo2.git("branch", "bogus");

        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        BranchSource branchSource = new BranchSource(new GitSCMSource("source-id", sampleRepo2.toString(), "", "*", "", false));
        mbp.getSourcesList().add(branchSource);
        // Note: this notification causes discovery of branches,
        // definition of MBP "leaf" jobs, and launch of builds,
        // so below we just make sure they complete and analyze
        // the outcomes.
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());

        WorkflowJob p1 = mbp.getItem("master");
        WorkflowRun b1 = p1.getLastBuild();
        r.waitForCompletion(b1);
        assertFalse(p1.isBuilding());
        r.assertBuildStatus(Result.FAILURE, b1);
        r.assertLogContains("ERROR: Version override not permitted for library branchylib", b1);
        r.assertLogContains("WorkflowScript: Loading libraries failed", b1);

        WorkflowJob p2 = mbp.getItem("feature");
        WorkflowRun b2 = p2.getLastBuild();
        r.waitForCompletion(b2);
        assertFalse(p2.isBuilding());
        r.assertBuildStatus(Result.FAILURE, b2);
        r.assertLogContains("ERROR: Version override not permitted for library branchylib", b2);
        r.assertLogContains("WorkflowScript: Loading libraries failed", b2);

        WorkflowJob p3 = mbp.getItem("bogus");
        WorkflowRun b3 = p3.getLastBuild();
        r.waitForCompletion(b3);
        assertFalse(p3.isBuilding());
        r.assertBuildStatus(Result.FAILURE, b3);
        r.assertLogContains("ERROR: Version override not permitted for library branchylib", b3);
        r.assertLogContains("WorkflowScript: Loading libraries failed", b3);
    }

    @Issue("JENKINS-69731")
    //@Ignore("Need help setting up MBP+SingleSCMSource")
    @Test public void checkDefaultVersion_MBPsingleBranch_staticStrings() throws Exception {
        // FAILS: Setup below does not instantiate any jobs!
        // (expected one for "feature")

        // Test that lc.setAllowBRANCH_NAME(false) does not
        // preclude fixed branch names (they should work),
        // like @Library('branchylib@master') when used
        // for MBP with "Single repository and branch" as
        // the SCM source.

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@master') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=master");
        sampleRepo2.git("checkout", "-b", "feature");
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@feature') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=feature");
        sampleRepo2.git("checkout", "-b", "bogus");
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@bogus') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=bogus");

        // TODO: Test also another MBP where we would
        // set options "List of branches to build" and
        // "Branch Specifier" to a different value than
        // the "name" of branchSource. While the "name"
        // becomes "BRANCH_NAME" envvar (and MBP job name
        // generated for the branch), the specifier is
        // what gets placed into SCMs list.

        // FAILS: Setup below does not instantiate any jobs!
        // (expected one for "feature")
        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        //GitSCM gitSCM = new GitSCM(sampleRepo2.toString());
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());
        BranchSource branchSource = new BranchSource(new SingleSCMSource("feature-id", "feature", gitSCM));
        mbp.getSourcesList().add(branchSource);
        // Note: this notification causes discovery of branches,
        // definition of MBP "leaf" jobs, and launch of builds,
        // so below we just make sure they complete and analyze
        // the outcomes.
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());
        assumeFalse("MBP should have generated 'feature' pipeline job", mbp.getItems().isEmpty());

        // In case of MBP with "Single repository and branch"
        // it only defines one job, so those for other branches
        // should be null:
        WorkflowJob p1 = mbp.getItem("master");
        assertNull(p1);

        WorkflowJob p2 = mbp.getItem("feature-id");
        assertNotNull(p2);
        WorkflowRun b2 = p2.getLastBuild();
        r.waitForCompletion(b2);
        assertFalse(p2.isBuilding());
        r.assertBuildStatusSuccess(b2);
        r.assertLogContains("Loading library branchylib@feature", b2);
        r.assertLogContains("something very special", b2);

        WorkflowJob p3 = mbp.getItem("bogus");
        assertNull(p3);
    }

    @Issue("JENKINS-69731")
    //@Ignore("Need help setting up MBP+SingleSCMSource")
    @Test public void checkDefaultVersion_MBPsingleBranch_BRANCH_NAME() throws Exception {
        // FAILS: Setup below does not instantiate any jobs!
        // (expected one for "feature")

        // Test that @Library('branchylib@${BRANCH_NAME}') works
        // also for MBP with "Single repository and branch" as
        // the SCM source.

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@${BRANCH_NAME}') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("branch", "feature");
        sampleRepo2.git("branch", "bogus");

        // TODO: Test also another MBP where we would
        // set options "List of branches to build" and
        // "Branch Specifier" to a different value than
        // the "name" of branchSource. While the "name"
        // becomes "BRANCH_NAME" envvar (and MBP job name
        // generated for the branch), the specifier is
        // what gets placed into SCMs list.

        // FAILS: Setup below does not instantiate any jobs!
        // (expected one for "feature")
        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        //GitSCM gitSCM = new GitSCM(sampleRepo2.toString());
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());
        BranchSource branchSource = new BranchSource(new SingleSCMSource("feature", "feature", gitSCM));
        mbp.getSourcesList().add(branchSource);
        // Note: this notification causes discovery of branches,
        // definition of MBP "leaf" jobs, and launch of builds,
        // so below we just make sure they complete and analyze
        // the outcomes.
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());
        assumeFalse("MBP should have generated 'feature' pipeline job", mbp.getItems().isEmpty());

        // In case of MBP with "Single repository and branch"
        // it only defines one job, so those for other branches
        // should be null:
        WorkflowJob p1 = mbp.getItem("master");
        assertNull(p1);

        WorkflowJob p2 = mbp.getItem("feature");
        assertNotNull(p2);
        WorkflowRun b2 = p2.getLastBuild();
        r.waitForCompletion(b2);
        assertFalse(p2.isBuilding());
        r.assertBuildStatusSuccess(b2);
        r.assertLogContains("Loading library branchylib@feature", b2);
        r.assertLogContains("something very special", b2);

        WorkflowJob p3 = mbp.getItem("bogus");
        assertNull(p3);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_singleBranch_staticStrings() throws Exception {
        // Test that lc.setAllowBRANCH_NAME(false) does not
        // preclude fixed branch names (they should work),
        // like @Library('branchylib@master') when used for
        // a simple "Pipeline" job with static SCM source.

        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceBRANCH_NAME(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@master') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=master");
        sampleRepo2.git("checkout", "-b", "feature");
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@feature') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=feature");
        sampleRepo2.git("checkout", "-b", "bogus");
        sampleRepo2.write("Jenkinsfile", "@Library('branchylib@bogus') import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=bogus");

        //GitSCM gitSCM = new GitSCM(sampleRepo2.toString());
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());

        WorkflowJob p0 = r.jenkins.createProject(WorkflowJob.class, "p0");
        p0.setDefinition(new CpsScmFlowDefinition(gitSCM, "Jenkinsfile"));
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();

        WorkflowRun b0 = r.buildAndAssertSuccess(p0);
        r.assertLogContains("Loading library branchylib@feature", b0);
        r.assertLogContains("something very special", b0);
    }

    @Issue("JENKINS-43802")
    @Test public void owner() throws Exception {
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("test", new SCMSourceRetriever(new NeedsOwnerSCMSource()))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('test@abc123') import libVersion; echo(/loaded lib #${libVersion()}/)", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("loaded lib #abc123", b);
        r.assertLogContains("Running in retrieve from p", b);
    }
    public static final class NeedsOwnerSCMSource extends SCMSource {
        @Override protected SCMRevision retrieve(String version, TaskListener listener, Item context) throws IOException, InterruptedException {
            if (context == null) {
                throw new AbortException("No context in retrieve!");
            } else {
                listener.getLogger().println("Running in retrieve from " + context.getFullName());
            }
            return new DummySCMRevision(version, new SCMHead("trunk"));
        }
        @Override public SCM build(SCMHead head, SCMRevision revision) {
            String version = ((DummySCMRevision) revision).version;
            return new SingleFileSCM("vars/libVersion.groovy", ("def call() {'" + version + "'}").getBytes());
        }
        private static final class DummySCMRevision extends SCMRevision {
            private final String version;
            DummySCMRevision(String version, SCMHead head) {
                super(head);
                this.version = version;
            }
            @Override public boolean equals(Object obj) {
                return obj instanceof DummySCMRevision && version.equals(((DummySCMRevision) obj).version);
            }
            @Override public int hashCode() {
                return version.hashCode();
            }
        }
        @Override protected void retrieve(SCMSourceCriteria criteria, SCMHeadObserver observer, SCMHeadEvent<?> event, TaskListener listener) throws IOException, InterruptedException {
            throw new IOException("not implemented");
        }
        @TestExtension("owner") public static final class DescriptorImpl extends SCMSourceDescriptor {}
    }

    @Test public void retry() throws Exception {
        WorkflowRun b = prepareRetryTests(new FailingSCMSource());
        r.assertLogContains("Failing 'checkout' on purpose!", b);
        r.assertLogContains("Retrying after 10 seconds", b);
    }

    @Test public void retryDuringFetch() throws Exception {
        WorkflowRun b = prepareRetryTests(new FailingSCMSourceDuringFetch());
        r.assertLogContains("Failing 'fetch' on purpose!", b);
        r.assertLogContains("Retrying after 10 seconds", b);
    }

    private WorkflowRun prepareRetryTests(SCMSource scmSource) throws Exception{
        final SCMSourceRetriever retriever = new SCMSourceRetriever(scmSource);
        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration("retry", retriever);
        final List<LibraryConfiguration> libraries = Collections.singletonList(libraryConfiguration);
        GlobalLibraries.get().setLibraries(libraries);
        final WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        final String script = "@Library('retry@master') import myecho; myecho()";
        final CpsFlowDefinition def = new CpsFlowDefinition(script, true);
        p.setDefinition(def);
        r.jenkins.setScmCheckoutRetryCount(1);

        return r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
    }

    @Test
    public void modernAndLegacyImpls() {
        SCMSourceRetriever.DescriptorImpl modern = lookupSingleton(SCMSourceRetriever.DescriptorImpl.class);

        containsInAnyOrder(modern.getSCMDescriptors(), contains(instanceOf(FakeModernSCM.DescriptorImpl.class)));
        containsInAnyOrder(modern.getSCMDescriptors(), contains(instanceOf(FakeAlsoModernSCM.DescriptorImpl.class)));
        containsInAnyOrder(modern.getSCMDescriptors(), not(contains(instanceOf(BasicSCMSource.DescriptorImpl.class))));
    }
    // Implementation of latest and greatest API
    public static final class FakeModernSCM extends SCMSource {
        @Override protected void retrieve(SCMSourceCriteria c, @NonNull SCMHeadObserver o, SCMHeadEvent<?> e, @NonNull TaskListener l) {}
        @Override public @NonNull SCM build(@NonNull SCMHead head, SCMRevision revision) { return null; }
        @TestExtension("modernAndLegacyImpls") public static final class DescriptorImpl extends SCMSourceDescriptor {}

        @Override
        protected SCMRevision retrieve(@NonNull String thingName, @NonNull TaskListener listener, Item context) throws IOException, InterruptedException {
            return super.retrieve(thingName, listener, context);
        }
    }
    // Implementation of second latest and second greatest API
    public static final class FakeAlsoModernSCM extends SCMSource {
        @Override protected void retrieve(SCMSourceCriteria c, @NonNull SCMHeadObserver o, SCMHeadEvent<?> e, @NonNull TaskListener l) {}
        @Override public @NonNull SCM build(@NonNull SCMHead head, SCMRevision revision) { return null; }
        @TestExtension("modernAndLegacyImpls") public static final class DescriptorImpl extends SCMSourceDescriptor {}

        @Override
        protected SCMRevision retrieve(@NonNull String thingName, @NonNull TaskListener listener) throws IOException, InterruptedException {
            return super.retrieve(thingName, listener);
        }
    }
    // No modern stuff
    public static class BasicSCMSource extends SCMSource {
        @Override protected void retrieve(SCMSourceCriteria c, @NonNull SCMHeadObserver o, SCMHeadEvent<?> e, @NonNull TaskListener l) {}
        @Override public @NonNull SCM build(@NonNull SCMHead head, SCMRevision revision) { return null; }
        @TestExtension("modernAndLegacyImpls") public static final class DescriptorImpl extends SCMSourceDescriptor {}
    }

    @Issue("JENKINS-66629")
    @Test public void renameDeletesOldLibsWorkspace() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
                new LibraryConfiguration("delete_removes_libs_workspace",
                        new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('delete_removes_libs_workspace@master') import myecho; myecho()", true));
        FilePath oldWs = r.jenkins.getWorkspaceFor(p).withSuffix("@libs");
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("something special", b);
        assertTrue(oldWs.exists());
        p.renameTo("p2");
        FilePath newWs = r.jenkins.getWorkspaceFor(p).withSuffix("@libs");
        assertFalse(oldWs.exists());
        assertFalse(newWs.exists());
        r.buildAndAssertSuccess(p);
        assertFalse(oldWs.exists());
        assertTrue(newWs.exists());
    }

    @Issue("JENKINS-66629")
    @Test public void deleteRemovesLibsWorkspace() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
                new LibraryConfiguration("delete_removes_libs_workspace",
                        new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('delete_removes_libs_workspace@master') import myecho; myecho()", true));
        FilePath ws = r.jenkins.getWorkspaceFor(p).withSuffix("@libs");
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("something special", b);
        assertTrue(ws.exists());
        p.delete();
        assertFalse(ws.exists());
    }

    @Issue("SECURITY-2441")
    @Test public void libraryNamesAreNotUsedAsCheckoutDirectories() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/globalLibVar.groovy", "def call() { echo('global library') }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration globalLib = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        globalLib.setDefaultVersion("master");
        globalLib.setImplicit(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(globalLib));
        // Create a folder library with the same name as the global library so it takes precedence.
        sampleRepoSvn.init();
        sampleRepoSvn.write("vars/folderLibVar.groovy", "def call() { jenkins.model.Jenkins.get().setSystemMessage('folder library') }");
        // Copy .git folder from the Git repo for the global library into the SVN repo for the folder library as data.
        FileUtils.copyDirectory(new File(sampleRepo.getRoot(), ".git"), new File(sampleRepoSvn.wc(), ".git"));
        sampleRepoSvn.svnkit("add", sampleRepoSvn.wc() + "/vars");
        sampleRepoSvn.svnkit("add", sampleRepoSvn.wc() + "/.git");
        sampleRepoSvn.svnkit("commit", "--message=init", sampleRepoSvn.wc());
        LibraryConfiguration folderLib = new LibraryConfiguration("library",
                new SCMSourceRetriever(new SubversionSCMSource(null, sampleRepoSvn.prjUrl())));
        folderLib.setDefaultVersion("trunk");
        folderLib.setImplicit(true);
        Folder f = r.jenkins.createProject(Folder.class, "folder1");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(folderLib)));
        // Create a job that uses the folder library, which will take precedence over the global library, since they have the same name.
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("folderLibVar()", true));
        // First build fails as expected since it is not trusted. The folder library is checked out.
        WorkflowRun b1 = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("Only using first definition of library library", b1);
        r.assertLogContains("Scripts not permitted to use staticMethod jenkins.model.Jenkins get", b1);
        // Attacker deletes the folder library, then reruns the build.
        // The existing checkout of the SVN repo should not be reused as the Git repo for the global library.
        f.getProperties().clear();
        WorkflowRun b2 = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("No such DSL method 'folderLibVar'", b2);
        assertThat(r.jenkins.getSystemMessage(), nullValue());
    }

    @Issue("SECURITY-2463")
    @Test public void checkoutDirectoriesAreNotReusedByDifferentScms() throws Exception {
        assumeFalse(Functions.isWindows()); // Checkout hook is not cross-platform.
        sampleRepo.init();
        sampleRepo.write("vars/foo.groovy", "def call() { echo('using global lib') }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration globalLib = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        globalLib.setDefaultVersion("master");
        globalLib.setImplicit(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(globalLib));
        // Create a folder library with the same name as the global library so it takes precedence.
        sampleRepoSvn.init();
        sampleRepoSvn.write("vars/foo.groovy", "def call() { echo('using folder lib') }");
        // Copy .git folder from the Git repo for the global library into the SVN repo for the folder library as data.
        File gitDirInSvnRepo = new File(sampleRepoSvn.wc(), ".git");
        FileUtils.copyDirectory(new File(sampleRepo.getRoot(), ".git"), gitDirInSvnRepo);
        String jenkinsRootDir = r.jenkins.getRootDir().toString();
        // Add a Git post-checkout hook to the .git folder in the SVN repo.
        Path postCheckoutHook = gitDirInSvnRepo.toPath().resolve("hooks/post-checkout");
        // Always create hooks directory for compatibility with https://github.com/jenkinsci/git-plugin/pull/1207.
        Files.createDirectories(postCheckoutHook.getParent());
        Files.write(postCheckoutHook, ("#!/bin/sh\ntouch '" + jenkinsRootDir + "/hook-executed'\n").getBytes(StandardCharsets.UTF_8));
        sampleRepoSvn.svnkit("add", sampleRepoSvn.wc() + "/vars");
        sampleRepoSvn.svnkit("add", sampleRepoSvn.wc() + "/.git");
        sampleRepoSvn.svnkit("propset", "svn:executable", "ON", sampleRepoSvn.wc() + "/.git/hooks/post-checkout");
        sampleRepoSvn.svnkit("commit", "--message=init", sampleRepoSvn.wc());
        LibraryConfiguration folderLib = new LibraryConfiguration("library",
                new SCMSourceRetriever(new SubversionSCMSource(null, sampleRepoSvn.prjUrl())));
        folderLib.setDefaultVersion("trunk");
        folderLib.setImplicit(true);
        Folder f = r.jenkins.createProject(Folder.class, "folder1");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(folderLib)));
        // Run the build using the folder library (which uses the SVN repo).
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("foo()", true));
        r.buildAndAssertSuccess(p);
        // Delete the folder library, and rerun the build so the global library is used.
        f.getProperties().clear();
        WorkflowRun b2 = r.buildAndAssertSuccess(p);
        assertFalse("Git checkout should not execute hooks from SVN repo", new File(r.jenkins.getRootDir(), "hook-executed").exists());
    }
}
