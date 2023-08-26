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
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.EnvironmentContributingAction;
import hudson.model.EnvironmentContributor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.BranchSpec;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.WorkspaceList;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import hudson.util.DescribableList;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
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
import hudson.plugins.git.extensions.impl.CloneOption;
import jenkins.plugins.git.traits.CloneOptionTrait;
import jenkins.plugins.git.traits.RefSpecsSCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTrait;
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
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.jenkinsci.plugins.workflow.libs.SCMBasedRetriever.PROHIBITED_DOUBLE_DOT;
import static org.junit.Assume.assumeFalse;
import org.jvnet.hudson.test.FlagRule;
import org.jvnet.hudson.test.LoggerRule;

public class SCMSourceRetrieverTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();
    @Rule public GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();
    @Rule public SubversionSampleRepoRule sampleRepoSvn = new SubversionSampleRepoRule();
    @Rule public FlagRule<Boolean> includeSrcTest = new FlagRule<>(() -> SCMBasedRetriever.INCLUDE_SRC_TEST_IN_LIBRARIES, v -> SCMBasedRetriever.INCLUDE_SRC_TEST_IN_LIBRARIES = v);
    @Rule public LoggerRule logging = new LoggerRule().record(SCMBasedRetriever.class, Level.FINE);

    // Repetitive helpers for test cases dealing with @Issue("JENKINS-69731") and others
    private void sampleRepo1ContentMaster() throws Exception {
        sampleRepo1ContentMaster(null);
    }

    private void sampleRepo1ContentMaster(String subdir) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo.init();
        sampleRepo.write(subdir + "vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", subdir + "vars");
        sampleRepo.git("commit", "--message=init");
    }

    private void sampleRepo1ContentMasterAddLibraryCommit() throws Exception {
        sampleRepo1ContentMasterAddLibraryCommit(null);
    }

    private void sampleRepo1ContentMasterAddLibraryCommit(String subdir) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something even more special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=library_commit");
    }

    private void sampleRepo1ContentMasterFeature() throws Exception {
        sampleRepo1ContentMasterFeature(null);
    }

    private void sampleRepo1ContentMasterFeature(String subdir) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo.init();
        sampleRepo.write(subdir + "vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", subdir + "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write(subdir + "vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", subdir + "vars");
        sampleRepo.git("commit", "--message=init");
    }

    private void sampleRepo1ContentMasterFeatureStable() throws Exception {
        sampleRepo1ContentMasterFeatureStable(null);
    }

    private void sampleRepo1ContentMasterFeatureStable(String subdir) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo.init();
        sampleRepo.write(subdir + "vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", subdir + "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "feature");
        sampleRepo.write(subdir + "vars/myecho.groovy", "def call() {echo 'something very special'}");
        sampleRepo.git("add", subdir + "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "-b", "stable");
        sampleRepo.write(subdir + "vars/myecho.groovy", "def call() {echo 'something reliable'}");
        sampleRepo.git("add", subdir + "vars");
        sampleRepo.git("commit", "--message=init");
    }

    private void sampleRepo2ContentMasterFeature() throws Exception {
        sampleRepo2ContentMasterFeature(null);
    }

    private void sampleRepo2ContentMasterFeature(String subdir) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo2.init();
        sampleRepo2.write(subdir + "vars/myecho2.groovy", "def call() {echo 'something weird'}");
        sampleRepo2.git("add", subdir + "vars");
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("checkout", "-b", "feature");
        sampleRepo2.write(subdir + "vars/myecho2.groovy", "def call() {echo 'something wonderful'}");
        sampleRepo2.git("add", subdir + "vars");
        sampleRepo2.git("commit", "--message=init");
    }

    private void sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME() throws Exception {
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(null, false);
    }

    private void sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(Boolean addJenkinsfileStatic) throws Exception {
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(null, addJenkinsfileStatic);
    }

    private void sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(String subdir) throws Exception {
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(subdir, false);
    }

    private void sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(String subdir, Boolean addJenkinsfileStatic) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo2.init();
        sampleRepo2.write(subdir + "Jenkinsfile", "@Library('branchylib@${BRANCH_NAME}') import myecho; myecho()");
        if (addJenkinsfileStatic) {
            sampleRepo2.write("Jenkinsfile-static", "@Library('branchylib@stable') import myecho; myecho()");
            sampleRepo2.git("add", subdir + "Jenkinsfile*");
        } else {
            sampleRepo2.git("add", subdir + "Jenkinsfile");
        }
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("branch", "feature");
        sampleRepo2.git("branch", "bogus");
    }

    private void sampleRepo2ContentUniqueMasterFeatureBogus_staticStrings() throws Exception {
        sampleRepo2ContentUniqueMasterFeatureBogus_staticStrings(null);
    }

    private void sampleRepo2ContentUniqueMasterFeatureBogus_staticStrings(String subdir) throws Exception {
        if (subdir != null && !(subdir.endsWith("/"))) subdir += "/";
        if (subdir == null) subdir = "";
        sampleRepo2.init();
        sampleRepo2.write(subdir + "Jenkinsfile", "@Library('branchylib@master') import myecho; myecho()");
        sampleRepo2.git("add", subdir + "Jenkinsfile");
        sampleRepo2.git("commit", "--message=master");
        sampleRepo2.git("checkout", "-b", "feature");
        sampleRepo2.write(subdir + "Jenkinsfile", "@Library('branchylib@feature') import myecho; myecho()");
        sampleRepo2.git("add", subdir + "Jenkinsfile");
        sampleRepo2.git("commit", "--message=feature");
        sampleRepo2.git("checkout", "-b", "bogus");
        sampleRepo2.write(subdir + "Jenkinsfile", "@Library('branchylib@bogus') import myecho; myecho()");
        sampleRepo2.git("add", subdir + "Jenkinsfile");
        sampleRepo2.git("commit", "--message=bogus");
    }

    @Issue("JENKINS-40408")
    @Test public void lease() throws Exception {
        sampleRepo1ContentMaster();
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
        sampleRepo1ContentMaster();
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
        sampleRepo1ContentMasterAddLibraryCommit();
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
        sampleRepo1ContentMaster();
        LibraryConfiguration lc = new LibraryConfiguration("dont_include_changes", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        lc.setIncludeInChangesets(false);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('dont_include_changes@master') import myecho; myecho()", true));
        FilePath base = r.jenkins.getWorkspaceFor(p).withSuffix("@libs").child("dont_include_changes");
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun a = r.buildAndAssertSuccess(p);
        }
        sampleRepo1ContentMasterAddLibraryCommit();
        try (WorkspaceList.Lease lease = r.jenkins.toComputer().getWorkspaceList().acquire(base)) {
            WorkflowRun b = r.buildAndAssertSuccess(p);
            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = b.getChangeSets();
            assertEquals(0, changeSets.size());
            r.assertLogNotContains("Retrying after 10 seconds", b);
        }
    }

    @Issue("JENKINS-38609")
    @Test public void libraryPath() throws Exception {
        sampleRepo1ContentMaster("sub/path");
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
        sampleRepo1ContentMaster("sub/path");
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
        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
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
        assumeFalse("SKIP by pre-test assumption: " +
                        "An externally provided BRANCH_NAME envvar interferes with tested logic",
                System.getenv("BRANCH_NAME") != null);

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);

        sampleRepo2ContentMasterFeature();
        SCMSourceRetriever scm2 = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo2.toString(), "", "*", "", true));
        LibraryConfiguration lc2 = new LibraryConfiguration("branchylib2", scm2);
        lc2.setDefaultVersion("master");
        lc2.setIncludeInChangesets(false);
        lc2.setAllowVersionOverride(true);
        lc2.setAllowBRANCH_NAME(true);
        lc2.setTraceDefaultedVersion(true);

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

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME();

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

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentUniqueMasterFeatureBogus_staticStrings();
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

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME();
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
    @Test public void checkDefaultVersion_MBPsingleBranch_staticStrings() throws Exception {
        // Test that lc.setAllowBRANCH_NAME(false) does not
        // preclude fixed branch names (they should work),
        // like @Library('branchylib@feature') when used
        // for MBP with "Single repository and branch" as
        // the SCM source.

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentUniqueMasterFeatureBogus_staticStrings();
        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());
        // We test an MBP with two leaf jobs where we
        // set options "List of branches to build"/
        // "Branch Specifier" to a same and a different
        // value than the "name" of branchSource.
        // While the "name" becomes "BRANCH_NAME" envvar
        // (and MBP job name generated for the branch),
        // the specifier is what gets placed into SCMs list.
        BranchSource branchSource1 = new BranchSource(
                new SingleSCMSource("feature-id1", "feature", gitSCM),
                new DefaultBranchPropertyStrategy(new BranchProperty[0]));
        BranchSource branchSource2 = new BranchSource(
                new SingleSCMSource("feature-id2", "featurette", gitSCM),
                new DefaultBranchPropertyStrategy(new BranchProperty[0]));
        mbp.getSourcesList().add(branchSource1);
        mbp.getSourcesList().add(branchSource2);
        mbp.save();
        // Rescan to actually define leaf jobs:
        mbp.scheduleBuild(0);
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("All Jenkins items: " + r.jenkins.getItems().toString());
        System.out.println("MBP sources: " + mbp.getSourcesList().toString());
        System.out.println("MBP source 0: " + mbp.getSourcesList().get(0).getSource().toString());
        System.out.println("MBP source 1: " + mbp.getSourcesList().get(1).getSource().toString());
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());
        assumeFalse("SKIP by pre-test assumption: " +
                "MBP should have generated 'feature' and 'featurette' pipeline job", mbp.getItems().size() != 2);

        // In case of MBP with "Single repository and branch"
        // it only defines one job (per single-branch source),
        // so those for other known branches should be null:
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

        // For fixed branch in @Library spec, we see the
        // SingleSCMSource checkout out the "*/feature"
        // specified in its GitSCM and so request the
        // @Library('branchylib@feature') spelled there.
        // And then MBP sets BRANCH_NAME='featurette'
        // (and leaf job) per SingleSCMSource "name".
        WorkflowJob p4 = mbp.getItem("featurette");
        assertNotNull(p4);
        WorkflowRun b4 = p4.getLastBuild();
        r.waitForCompletion(b4);
        assertFalse(p4.isBuilding());
        r.assertBuildStatusSuccess(b4);
        System.out.println("Envvar BRANCH_NAME set into 'featurette' job: " + b4.getEnvironment().get("BRANCH_NAME"));
        // We use same gitSCM source, and so same static
        // version of the library:
        r.assertLogContains("Loading library branchylib@feature", b4);
        r.assertLogContains("something very special", b4);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_MBPsingleBranch_BRANCH_NAME() throws Exception {
        // Test that @Library('branchylib@${BRANCH_NAME}') works
        // also for MBP with "Single repository and branch" as
        // the SCM source.

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME();
        WorkflowMultiBranchProject mbp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "mbp");
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());
        // We test an MBP with two leaf jobs where we
        // set options "List of branches to build"/
        // "Branch Specifier" to a same and a different
        // value than the "name" of branchSource.
        // While the "name" becomes "BRANCH_NAME" envvar
        // (and MBP job name generated for the branch),
        // the specifier is what gets placed into SCMs list.
        BranchSource branchSource1 = new BranchSource(
                new SingleSCMSource("feature-id1", "feature", gitSCM),
                new DefaultBranchPropertyStrategy(new BranchProperty[0]));
        BranchSource branchSource2 = new BranchSource(
                new SingleSCMSource("feature-id2", "featurette", gitSCM),
                new DefaultBranchPropertyStrategy(new BranchProperty[0]));
        mbp.getSourcesList().add(branchSource1);
        mbp.getSourcesList().add(branchSource2);
        mbp.save();
        // Rescan to actually define leaf jobs:
        mbp.scheduleBuild(0);
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        System.out.println("All Jenkins items: " + r.jenkins.getItems().toString());
        System.out.println("MBP sources: " + mbp.getSourcesList().toString());
        System.out.println("MBP source 0: " + mbp.getSourcesList().get(0).getSource().toString());
        System.out.println("MBP source 1: " + mbp.getSourcesList().get(1).getSource().toString());
        System.out.println("Jobs generated by MBP: " + mbp.getItems().toString());
        assumeFalse("SKIP by pre-test assumption: " +
                "MBP should have generated 'feature' and 'featurette' pipeline job", mbp.getItems().size() != 2);

        // In case of MBP with "Single repository and branch"
        // it only defines one job (per single-branch source),
        // so those for other known branches should be null:
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

        // For fixed branch in @Library spec, we see the
        // SingleSCMSource checkout out the "*/feature"
        // specified in its GitSCM and so evaluate the
        // @Library('branchylib@${BRANCH_NAME}')...
        // And then MBP sets BRANCH_NAME='featurette'
        // (and leaf job) per SingleSCMSource "name".
        WorkflowJob p4 = mbp.getItem("featurette");
        assertNotNull(p4);
        WorkflowRun b4 = p4.getLastBuild();
        r.waitForCompletion(b4);
        assertFalse(p4.isBuilding());
        r.assertBuildStatusSuccess(b4);
        System.out.println("Envvar BRANCH_NAME set into 'featurette' job: " + b4.getEnvironment().get("BRANCH_NAME"));
        // Library does not have a "featurette" branch,
        // so if that source were tried according to the
        // single-branch source name, should fall back
        // to "master". But if it were tried according
        // to the actual branch of pipeline script, it
        // should use "feature".
        // For now, I've sided with the MBP plugin which
        // goes to great lengths to make-believe that
        // the "name" specified in config is the branch
        // name (also setting it into the WorkflowJob
        // BranchJobProperty), even if it does not exist
        // in actual SCM.
        r.assertLogContains("Loading library branchylib@master", b4);
        r.assertLogContains("something special", b4);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_singleBranch_staticStrings() throws Exception {
        // Test that lc.setAllowBRANCH_NAME(false) does not
        // preclude fixed branch names (they should work),
        // like @Library('branchylib@master') when used for
        // a simple "Pipeline" job with static SCM source.

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowBRANCH_NAME(false);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentUniqueMasterFeatureBogus_staticStrings();
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

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_singleBranch_BRANCH_NAME() throws Exception {
        // Test that lc.setAllowBRANCH_NAME(true) enables
        // @Library('branchylib@${BRANCH_NAME}') also for
        // a simple "Pipeline" job with static SCM source,
        // and that even lc.setAllowVersionOverride(false)
        // does not intervene here.
        assumeFalse("SKIP by pre-test assumption: " +
                        "An externally provided BRANCH_NAME envvar interferes with tested logic",
            System.getenv("BRANCH_NAME") != null);

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME();

        // Get a non-default branch loaded for this single-branch build:
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

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_singleBranch_BRANCH_NAME_after_staticStrings() throws Exception {
        // Test that using @Library('branchylib@static')
        // in one build of a job definition, and then a
        // @Library('branchylib@${BRANCH_NAME}') next,
        // both behave well.
        // For context see e.g. WorkflowJob.getSCMs():
        // https://github.com/jonsten/workflow-job-plugin/blob/master/src/main/java/org/jenkinsci/plugins/workflow/job/WorkflowJob.java#L539
        // https://issues.jenkins.io/browse/JENKINS-40255
        // how it looks at a history of EARLIER builds
        // (preferring successes) and not at the current
        // job definition.
        // Note: being a piece of test-driven development,
        // this test does not fail as soon as it gets an
        // "unexpected" log message (so far expected due
        // to the bug being hunted), but counts the faults
        // and asserts in the end whether there were none.
        assumeFalse("SKIP by pre-test assumption: " +
                        "An externally provided BRANCH_NAME envvar interferes with tested logic",
                System.getenv("BRANCH_NAME") != null);

        sampleRepo1ContentMasterFeatureStable();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME(true);

        // Get a non-default branch loaded for this single-branch build:
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());

        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();

        // First run a job definition with a fixed library version,
        // e.g. like a custom Replay might in the field, or before
        // redefining an "inline" pipeline to one coming from SCM.
        // Pepper job history with successes and faults:
        long failCount = 0;
        WorkflowJob p1 = r.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("@Library('branchylib@stable') import myecho; myecho()", true));
        WorkflowRun b1 = r.buildAndAssertStatus(Result.FAILURE, p1);
        r.assertLogContains("ERROR: Version override not permitted for library branchylib", b1);
        r.assertLogContains("WorkflowScript: Loading libraries failed", b1);

        // Use default version:
        p1.setDefinition(new CpsFlowDefinition("@Library('branchylib') import myecho; myecho()", true));
        WorkflowRun b2 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@master", b2);
        r.assertLogContains("something special", b2);

        // Now redefine the same job to come from SCM and use a
        // run-time resolved library version (WorkflowJob getSCMs
        // behavior should not be a problem):
        p1.setDefinition(new CpsScmFlowDefinition(gitSCM, "Jenkinsfile"));
        WorkflowRun b3 = r.buildAndAssertSuccess(p1);
        try {
            // In case of misbehavior this loads "master" version:
            r.assertLogContains("Loading library branchylib@feature", b3);
            r.assertLogContains("something very special", b3);
        } catch (AssertionError ae) {
            failCount++;
            // Make sure it was not some other problem:
            r.assertLogContains("Loading library branchylib@master", b3);
            r.assertLogContains("something special", b3);
        }

        // Override with a static version:
        lc.setAllowVersionOverride(true);
        p1.setDefinition(new CpsFlowDefinition("@Library('branchylib@stable') import myecho; myecho()", true));
        WorkflowRun b4 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@stable", b4);
        r.assertLogContains("something reliable", b4);

        // Dynamic version again:
        p1.setDefinition(new CpsScmFlowDefinition(gitSCM, "Jenkinsfile"));
        WorkflowRun b5 = r.buildAndAssertSuccess(p1);
        try {
            // In case of misbehavior this loads "stable" version:
            r.assertLogContains("Loading library branchylib@feature", b5);
            r.assertLogContains("something very special", b5);
        } catch (AssertionError ae) {
            failCount++;
            // Make sure it was not some other problem:
            r.assertLogContains("Loading library branchylib@stable", b5);
            r.assertLogContains("something reliable", b5);
        }

        // SCM source pointing at static version
        p1.setDefinition(new CpsScmFlowDefinition(gitSCM, "Jenkinsfile-static"));
        WorkflowRun b6 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@stable", b6);
        r.assertLogContains("something reliable", b6);

        // Dynamic version again; seems with the change of filename it works okay:
        p1.setDefinition(new CpsScmFlowDefinition(gitSCM, "Jenkinsfile"));
        WorkflowRun b7 = r.buildAndAssertSuccess(p1);
        try {
            // In case of misbehavior this loads "stable" version:
            r.assertLogContains("Loading library branchylib@feature", b7);
            r.assertLogContains("something very special", b7);
        } catch (AssertionError ae) {
            failCount++;
            // Make sure it was not some other problem:
            r.assertLogContains("Loading library branchylib@stable", b7);
            r.assertLogContains("something reliable", b7);
        }

        assertEquals("All BRANCH_NAME resolutions are expected to checkout feature",0, failCount);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_singleBranch_BRANCH_NAME_doubleQuotes() throws Exception {
        // Similar to above, the goal of this test is to
        // verify that substitution of ${BRANCH_NAME} is
        // not impacted by content of Groovy variables.
        // The @Library annotation version resolution
        // happens before Groovy->Java->... compilation.
        // Note that at this time LibraryDecorator.java
        // forbids use of non-constant strings; so this
        // test would be dynamically skipped as long as
        // this behavior happens.
        // TODO: If this behavior does change, extend
        // the test to also try ${env.VARNAME} confusion.

        assumeFalse("SKIP by pre-test assumption: " +
                        "An externally provided BRANCH_NAME envvar interferes with tested logic",
                System.getenv("BRANCH_NAME") != null);

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2.init();
        sampleRepo2.write("Jenkinsfile", "BRANCH_NAME='whatever'; @Library(\"branchylib@${BRANCH_NAME}\") import myecho; myecho()");
        sampleRepo2.git("add", "Jenkinsfile");
        sampleRepo2.git("commit", "--message=init");
        sampleRepo2.git("branch", "feature");
        sampleRepo2.git("branch", "bogus");

        // Get a non-default branch loaded for this single-branch build:
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());

        WorkflowJob p1 = r.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsScmFlowDefinition(gitSCM, "Jenkinsfile"));
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();
        p1.scheduleBuild2(0);
        r.waitUntilNoActivity();
        WorkflowRun b1 = p1.getLastBuild();
        r.waitForCompletion(b1);
        assertFalse(p1.isBuilding());

        // LibraryDecorator may forbid use of double-quotes
        try {
            r.assertBuildStatus(Result.FAILURE, b1);
            r.assertLogContains("WorkflowScript: @Library value", b1);
            r.assertLogContains("was not a constant; did you mean to use the", b1);
            r.assertLogContains("step instead?", b1);
            // assertions survived, skip the test
            // not exactly "pre-test" but oh well
            assumeFalse("SKIP by pre-test assumption: " +
                    "LibraryDecorator forbids use of double-quotes for @Library annotation", true);
        } catch(AssertionError x) {
            // Chosen library version should not be "whatever"
            // causing fallback to "master", but "feature" per
            // pipeline SCM branch name.
            r.assertBuildStatusSuccess(b1);
            r.assertLogContains("Loading library branchylib@feature", b1);
            r.assertLogContains("something very special", b1);
        }
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_singleBranch_BRANCH_NAME_lightweight() throws Exception {
        // Test that lightweight checkouts from SCM allow
        // @Library('branchylib@${BRANCH_NAME}') to see
        // sufficient SCM context to determine the branch.
        assumeFalse("SKIP by pre-test assumption: " +
                        "An externally provided BRANCH_NAME envvar interferes with tested logic",
                System.getenv("BRANCH_NAME") != null);

        sampleRepo1ContentMasterFeature();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(false);
        lc.setAllowBRANCH_NAME(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // Inspired in part by tests like
        // https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/NoTriggerBranchPropertyWorkflowTest.java#L132
        sampleRepo2ContentSameMasterFeatureBogus_BRANCH_NAME();

        // Get a non-default branch loaded for this single-branch build:
        GitSCM gitSCM = new GitSCM(
                GitSCM.createRepoList(sampleRepo2.toString(), null),
                Collections.singletonList(new BranchSpec("*/feature")),
                null, null, Collections.emptyList());

        CpsScmFlowDefinition csfd = new CpsScmFlowDefinition(gitSCM, "Jenkinsfile");
        csfd.setLightweight(true);
        WorkflowJob p0 = r.jenkins.createProject(WorkflowJob.class, "p0");
        p0.setDefinition(csfd);
        sampleRepo2.notifyCommit(r);
        r.waitUntilNoActivity();

        WorkflowRun b0 = r.buildAndAssertSuccess(p0);
        r.assertLogContains("Loading library branchylib@feature", b0);
        r.assertLogContains("something very special", b0);
    }

    @Issue("JENKINS-69731")
    @Test public void checkDefaultVersion_inline_allowVersionEnvvar() throws Exception {
        // Test that @Library('branchylib@${env.TEST_VAR_NAME}')
        // is resolved with the TEST_VAR_NAME="feature" in environment.

        // Do not let caller-provided BRANCH_NAME interfere here
        assumeFalse("SKIP by pre-test assumption: " +
                        "An externally provided TEST_VAR_NAME envvar interferes with tested logic",
                System.getenv("TEST_VAR_NAME") != null);

        sampleRepo1ContentMasterFeatureStable();
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
        LibraryConfiguration lc = new LibraryConfiguration("branchylib", scm);
        lc.setDefaultVersion("master");
        lc.setIncludeInChangesets(false);
        lc.setAllowVersionOverride(true);
        lc.setAllowVersionEnvvar(true);
        lc.setTraceDefaultedVersion(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));

        // TEST_VAR_NAME for job not set - fall back to default
        WorkflowJob p0 = r.jenkins.createProject(WorkflowJob.class, "p0");
        p0.setDefinition(new CpsFlowDefinition("@Library('branchylib@${env.TEST_VAR_NAME}') import myecho; myecho()", true));
        WorkflowRun b0 = r.buildAndAssertSuccess(p0);
        r.assertLogContains("Loading library branchylib@master", b0);
        r.assertLogContains("something special", b0);

        // TEST_VAR_NAME injected into env, use its value for library checkout
        // https://github.com/jenkinsci/envinject-plugin/blob/master/src/test/java/org/jenkinsci/plugins/envinject/EnvInjectPluginActionTest.java
        WorkflowJob p1 = r.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("@Library('branchylib@${env.TEST_VAR_NAME}') import myecho; myecho(); try { echo \"Groovy TEST_VAR_NAME='${TEST_VAR_NAME}'\"; } catch (groovy.lang.MissingPropertyException mpe) { echo \"Groovy TEST_VAR_NAME missing: ${mpe.getMessage()}\"; } ; echo \"env.TEST_VAR_NAME='${env.TEST_VAR_NAME}'\"", true));

        // Inject envvar to server global settings:
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = r.jenkins.getGlobalNodeProperties();
        List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList = globalNodeProperties.getAll(EnvironmentVariablesNodeProperty.class);

        EnvironmentVariablesNodeProperty newEnvVarsNodeProperty = null;
        EnvVars envVars = null;

        if (envVarsNodePropertyList == null || envVarsNodePropertyList.isEmpty()) {
            newEnvVarsNodeProperty = new EnvironmentVariablesNodeProperty();
            globalNodeProperties.add(newEnvVarsNodeProperty);
            envVars = newEnvVarsNodeProperty.getEnvVars();
        } else {
            envVars = envVarsNodePropertyList.get(0).getEnvVars();
        }
        envVars.put("TEST_VAR_NAME", "stable");
        r.jenkins.save();

        WorkflowRun b1 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@stable", b1);
        r.assertLogContains("something reliable", b1);

        // Now try a more direct way to inject environment
        // variables into a Job/Run without extra plugins:

        // See research commented at
        // https://github.com/jenkinsci/pipeline-groovy-lib-plugin/pull/19#discussion_r990781686

        // General override idea was lifted from
        // https://github.com/jenkinsci/subversion-plugin/blob/master/src/test/java/hudson/scm/SubversionSCMTest.java#L1383
        // test-case recursiveEnvironmentVariables()

        // Per https://github.com/jenkinsci/jenkins/blob/031f40c50899ec4e5fa4d886a1c006a5330f2627/core/src/main/java/hudson/ExtensionList.java#L296
        // in implementation of `add(index, T)` the index is ignored.
        // So we save a copy in same order, drop the list, add our
        // override as the only entry (hence highest priority)
        // and re-add the original list contents.
        ExtensionList<EnvironmentContributor> ecList = EnvironmentContributor.all();
        List<EnvironmentContributor> ecOrig = new ArrayList();
        for (EnvironmentContributor ec : ecList) {
            ecOrig.add(ec);
        }
        ecList.removeAll(ecOrig);
        assumeFalse("SKIP by pre-test assumption: " +
                "EnvironmentContributor.all() should be empty now", !ecList.isEmpty());

        ecList.add(new EnvironmentContributor() {
            @Override public void buildEnvironmentFor(Run run, EnvVars ev, TaskListener tl) throws IOException, InterruptedException {
                if (tl != null)
                    tl.getLogger().println("[DEBUG:RUN] Injecting TEST_VAR_NAME='feature' to EnvVars");
                ev.put("TEST_VAR_NAME", "feature");
            }
            @Override public void buildEnvironmentFor(Job run, EnvVars ev, TaskListener tl) throws IOException, InterruptedException {
                if (tl != null)
                    tl.getLogger().println("[DEBUG:JOB] Injecting TEST_VAR_NAME='feature' to EnvVars");
                ev.put("TEST_VAR_NAME", "feature");
            }
        });
        for (EnvironmentContributor ec : ecOrig) {
            ecList.add(ec);
        }

        p1.scheduleBuild2(0);
        r.waitUntilNoActivity();
        WorkflowRun b2 = p1.getLastBuild();
        r.waitForCompletion(b2);
        assertFalse(p1.isBuilding());
        r.assertBuildStatusSuccess(b2);

        System.out.println("[DEBUG:EXT:p1b2] wfJob env: " + p1.getEnvironment(null, null));
        System.out.println("[DEBUG:EXT:p1b2] wfRun env: " + b2.getEnvironment());
        System.out.println("[DEBUG:EXT:p1b2] wfRun envContribActions: " + b2.getActions(EnvironmentContributingAction.class));

        // Our first try is expected to fail currently, since
        // WorkflowRun::getEnvironment() takes "env" from
        // super-class, and overlays with envvars from global
        // configuration. However, if in the future behavior
        // of workflow changes, it is not consequential - just
        // something to adjust "correct" expectations for.
        r.assertLogContains("Loading library branchylib@stable", b2);
        r.assertLogContains("something reliable", b2);

        // For the next try, however, we remove global config
        // part and expect the injected envvar to take hold:
        envVars.remove("TEST_VAR_NAME");
        r.jenkins.save();

        WorkflowRun b3 = r.buildAndAssertSuccess(p1);

        System.out.println("[DEBUG:EXT:p1b3] wfJob env: " + p1.getEnvironment(null, null));
        System.out.println("[DEBUG:EXT:p1b3] wfRun env: " + b3.getEnvironment());
        System.out.println("[DEBUG:EXT:p1b3] wfRun envContribActions: " + b3.getActions(EnvironmentContributingAction.class));

        r.assertLogContains("Loading library branchylib@feature", b3);
        r.assertLogContains("something very special", b3);

        // Below we do a similar trick with built-in agent settings
        // which (as a Computer=>Node) has lowest priority behind
        // global and injected envvars (see Run::getEnvironment()).
        // Check with the injected envvars (they override) like above
        // first, and with ecList contents restored to ecOrig state
        // only (so only Computer envvars are applied).
        // Trick here is that the "jenkins.model.Jenkins" is inherited
        // from Node and its toComputer() returns the "built-in" (nee
        // "master"), instance of "hudson.model.Hudson$MasterComputer"
        // whose String getName() is actually empty.
        // Pipeline scripts are initially processed only by this node,
        // further run on the controller, and then the actual work is
        // distributed to agents (often via remoting proxy methods)
        // if any are defined.
        Computer builtInComputer = r.jenkins.toComputer();
        Node builtInNode = builtInComputer.getNode();
/*
        EnvironmentVariablesNodeProperty builtInEnvProp = builtInNode.getNodeProperty(EnvironmentVariablesNodeProperty.class);

        if (builtInEnvProp == null) {
            builtInEnvProp = new EnvironmentVariablesNodeProperty();
            //builtInEnvProp.setNode(builtInNode);
            builtInNode.getNodeProperties().add(builtInEnvProp);
        }
        EnvVars builtInEnvVars = builtInEnvProp.getEnvVars();
        builtInEnvVars.put("TEST_VAR_NAME", "stable");
        builtInEnvProp.buildEnvVars(new EnvVars("TEST_VAR_NAME", "stable"), null);
 */
        builtInNode.getNodeProperties().add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("TEST_VAR_NAME", "stable")));
        r.jenkins.save();
        builtInNode.save();

        System.out.println("[DEBUG] Restart the 'built-in' Computer connection to clear its cachedEnvironment and recognize added envvar");
        builtInComputer.setTemporarilyOffline(true, new OfflineCause.ByCLI("Restart built-in to reread envvars config"));
        builtInComputer.waitUntilOffline();
        builtInComputer.disconnect(new OfflineCause.ByCLI("Restart built-in to reread envvars config"));
        r.waitUntilNoActivity();
        Thread.sleep(3000);
        builtInComputer.setTemporarilyOffline(false, null);
        builtInComputer.connect(true);
        builtInComputer.waitUntilOnline();

        System.out.println("[DEBUG] builtIn node env: " + builtInComputer.getEnvironment());

        // Both injected var and build node envvar setting present;
        // injected var wins:
        WorkflowRun b4 = r.buildAndAssertSuccess(p1);
        System.out.println("[DEBUG:EXT:p1b4] wfJob env: " + p1.getEnvironment(null, null));
        System.out.println("[DEBUG:EXT:p1b4] wfRun env: " + b4.getEnvironment());
        System.out.println("[DEBUG:EXT:p1b4] wfRun envContribActions: " + b4.getActions(EnvironmentContributingAction.class));
        r.assertLogContains("Loading library branchylib@feature", b4);
        r.assertLogContains("something very special", b4);
        r.assertLogContains("Groovy TEST_VAR_NAME='feature'", b4);
        r.assertLogContains("env.TEST_VAR_NAME='feature'", b4);

        // Only build agent envvars are present: drop all,
        // add back original (before our mock above):
        List<EnvironmentContributor> ecCurr = new ArrayList();
        for (EnvironmentContributor ec : ecList) {
            ecCurr.add(ec);
        }
        ecList.removeAll(ecCurr);
        for (EnvironmentContributor ec : ecOrig) {
            ecList.add(ec);
        }

        System.out.println("[DEBUG:EXT:p1b5] EnvironmentContributor.all(): " + EnvironmentContributor.all());
        System.out.println("[DEBUG:EXT:p1b5] builtIn node env: " + builtInComputer.getEnvironment());
        System.out.println("[DEBUG:EXT:p1b5] wfJob env in builtIn: " + p1.getEnvironment(builtInNode, null));
        System.out.println("[DEBUG:EXT:p1b5] wfJob env (without node): " + p1.getEnvironment(null, null));
        WorkflowRun b5 = r.buildAndAssertSuccess(p1);
        System.out.println("[DEBUG:EXT:p1b5] wfRun env: " + b5.getEnvironment());
        System.out.println("[DEBUG:EXT:p1b5] wfRun envContribActions: " + b5.getActions(EnvironmentContributingAction.class));
        r.assertLogContains("Loading library branchylib@stable", b5);
        r.assertLogContains("something reliable", b5);
        // Why oh why is the build agent's (cached) envvar not resolved
        // even after reconnect?.. Probably a burden of "built-in" until
        // Jenkins restart?..
        r.assertLogContains("Groovy TEST_VAR_NAME missing", b5);
        r.assertLogContains("env.TEST_VAR_NAME='null'", b5);

        // Let's try just that - restart Jenkins to fully reinit the
        // built-in node with its config:
        r.jenkins.reload();
        r.waitUntilNoActivity();

        // Feed it to Java reflection, to clear the internal cache...
        Field ccc = Computer.class.getDeclaredField("cachedEnvironment");
        ccc.setAccessible(true);
        ccc.set(builtInComputer, null);

        // Still, "built-in" node's envvars are ignored (technically they -
        // now that the cache is cleared - reload global config values for
        // the "MasterComputer" as its own). Checked on standalone instance
        // configured interactively that even a complete Jenkins restart
        // does not let configured "built-in" node envvars become recognized.
        // Loosely related to https://github.com/jenkinsci/jenkins/pull/1728
        // So we keep this test here as a way to notice if core functionality
        // ever changes.
        WorkflowRun b6 = r.buildAndAssertSuccess(p1);
        r.assertLogContains("Loading library branchylib@stable", b6);
        r.assertLogContains("something reliable", b6);
        r.assertLogContains("Groovy TEST_VAR_NAME missing", b6);
        r.assertLogContains("env.TEST_VAR_NAME='null'", b6);
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
        sampleRepo1ContentMaster();
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
        sampleRepo1ContentMaster();
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

    @Test public void cloneMode() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.write("README.md", "Summary");
        sampleRepo.git("rm", "file");
        sampleRepo.git("add", ".");
        sampleRepo.git("commit", "--message=init");
        GitSCMSource src = new GitSCMSource(sampleRepo.toString());
        CloneOption cloneOption = new CloneOption(true, true, null, null);
        cloneOption.setHonorRefspec(true);
        src.setTraits(List.<SCMSourceTrait>of(new CloneOptionTrait(cloneOption), new RefSpecsSCMSourceTrait("+refs/heads/master:refs/remotes/origin/master")));
        SCMSourceRetriever scm = new SCMSourceRetriever(src);
        LibraryConfiguration lc = new LibraryConfiguration("echoing", scm);
        lc.setIncludeInChangesets(false);
        scm.setClone(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('echoing@master') import myecho; myecho()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        assertFalse(r.jenkins.getWorkspaceFor(p).withSuffix("@libs").isDirectory());
        r.assertLogContains("something special", b);
        r.assertLogContains("Using shallow clone with depth 1", b);
        r.assertLogContains("Avoid fetching tags", b);
        r.assertLogNotContains("+refs/heads/*:refs/remotes/origin/*", b);
        File[] libDirs = new File(b.getRootDir(), "libs").listFiles(File::isDirectory);
        assertThat(libDirs, arrayWithSize(1));
        String[] entries = libDirs[0].list();
        assertThat(entries, arrayContainingInAnyOrder("vars"));
    }

    @Test public void cloneModeLibraryPath() throws Exception {
        sampleRepo.init();
        sampleRepo.write("sub/path/vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("root_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("sub/path/");
        scm.setClone(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('root_sub_path@master') import myecho; myecho()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("something special", b);
        File[] libDirs = new File(b.getRootDir(), "libs").listFiles(File::isDirectory);
        assertThat(libDirs, arrayWithSize(1));
        String[] entries = libDirs[0].list();
        assertThat(entries, arrayContainingInAnyOrder("vars"));
    }

    @Test public void cloneModeLibraryPathSecurity() throws Exception {
        sampleRepo.init();
        sampleRepo.write("sub/path/vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("root_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("sub/path/../../../jenkins_home/foo");
        scm.setClone(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('root_sub_path@master') import myecho; myecho()", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("Library path may not contain '..'", b);
    }

    @Test public void cloneModeExcludeSrcTest() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.write("src/test/X.groovy", "// irrelevant");
        sampleRepo.write("README.md", "Summary");
        sampleRepo.git("add", ".");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("echoing", scm);
        lc.setIncludeInChangesets(false);
        scm.setClone(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('echoing@master') import myecho; myecho()", true));
        SCMBasedRetriever.INCLUDE_SRC_TEST_IN_LIBRARIES = false;
        WorkflowRun b = r.buildAndAssertSuccess(p);
        assertFalse(r.jenkins.getWorkspaceFor(p).withSuffix("@libs").isDirectory());
        r.assertLogContains("something special", b);
        r.assertLogContains("Excluding src/test/ from checkout", b);
    }

    @Test public void cloneModeIncludeSrcTest() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo(/got ${new test.X().m()}/)}");
        sampleRepo.write("src/test/X.groovy", "package test; class X {def m() {'something special'}}");
        sampleRepo.write("README.md", "Summary");
        sampleRepo.git("add", ".");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("echoing", scm);
        lc.setIncludeInChangesets(false);
        scm.setClone(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('echoing@master') import myecho; myecho()", true));
        SCMBasedRetriever.INCLUDE_SRC_TEST_IN_LIBRARIES = true;
        WorkflowRun b = r.buildAndAssertSuccess(p);
        assertFalse(r.jenkins.getWorkspaceFor(p).withSuffix("@libs").isDirectory());
        r.assertLogContains("got something special", b);
        r.assertLogNotContains("Excluding src/test/ from checkout", b);
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
        assumeFalse("SKIP by pre-test assumption: " +
                        "checkoutDirectoriesAreNotReusedByDifferentScms() is " +
                "skipped on Windows: Checkout hook is not cross-platform",
                Functions.isWindows());
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
