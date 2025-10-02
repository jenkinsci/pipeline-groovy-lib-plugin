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

import com.cloudbees.groovy.cps.CpsTransformer;
import com.cloudbees.hudson.plugins.folder.Folder;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.junit.jupiter.WithGitSampleRepo;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

@WithGitSampleRepo
class RestartTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    @RegisterExtension
    private final JenkinsSessionExtension extension = new JenkinsSessionExtension();
    private GitSampleRepoRule sampleRepo;

    @BeforeEach
    void beforeEach(GitSampleRepoRule repo) {
        sampleRepo = repo;
    }

    @Test
    void smokes() throws Throwable {
        extension.then(j -> {
                sampleRepo.init();
                sampleRepo.write("src/pkg/Slow.groovy", "package pkg; class Slow {static void wait(script) {script.semaphore 'wait-class'}}");
                sampleRepo.write("vars/slow.groovy", "def call() {semaphore 'wait-var'}");
                sampleRepo.git("add", "src", "vars");
                sampleRepo.git("commit", "--message=init");
                GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("@Library('stuff@master') import pkg.Slow; echo 'at the beginning'; Slow.wait(this); echo 'in the middle'; slow(); echo 'at the end'", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-class/1", b);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait-class/1", null);
                SemaphoreStep.waitForStart("wait-var/1", b);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait-var/1", null);
                j.assertLogContains("at the end", j.waitForCompletion(b));
                assertEquals(1, b.getActions(LibrariesAction.class).size());
        });
    }

    @Test
    void replay() throws Throwable {
        final String initialScript = "def call() {semaphore 'wait'; echo 'initial content'}";
        extension.then(j -> {
                sampleRepo.init();
                sampleRepo.write("vars/slow.groovy", initialScript);
                sampleRepo.git("add", "vars");
                sampleRepo.git("commit", "--message=init");
                Folder d = j.jenkins.createProject(Folder.class, "d");
                d.getProperties().add(new FolderLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true))))));
                WorkflowJob p = d.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("@Library('stuff@master') _ = slow()", true));
                p.save(); // TODO should probably be implicit in setDefinition
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b1);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("d/p", WorkflowJob.class);
                WorkflowRun b1 = p.getLastBuild();
                SemaphoreStep.success("wait/1", null);
                j.assertLogContains("initial content", j.waitForCompletion(b1));
                ReplayAction ra = b1.getAction(ReplayAction.class);
                assertEquals(Collections.singletonMap("slow", initialScript), ra.getOriginalLoadedScripts());
                WorkflowRun b2 = (WorkflowRun) ra.run(ra.getOriginalScript(), Collections.singletonMap("slow", initialScript.replace("initial", "subsequent"))).waitForStart();
                SemaphoreStep.waitForStart("wait/2", b2);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("d/p", WorkflowJob.class);
                WorkflowRun b2 = p.getLastBuild();
                SemaphoreStep.success("wait/2", null);
                j.assertLogContains("subsequent content", j.waitForCompletion(b2));
        });
    }

    @Issue("JENKINS-39719")
    @Test
    void syntheticMethodOverride() throws Throwable {
        extension.then(j -> {
                sampleRepo.init();
                sampleRepo.write("src/p/MyTest.groovy", "package p; class MyTest {def mytest1() {}}");
                sampleRepo.write("src/p/MyOtherTest.groovy", "package p; class MyOtherTest {def test1() {}; def test2() {}}");
                sampleRepo.git("add", "src");
                sampleRepo.git("commit", "--message=init");
                GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("test", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("@Library('test@master') _; import p.MyTest; import p.MyOtherTest; class MyTestExtended extends MyTest {def mytestfunction() {}}", true));
                j.buildAndAssertSuccess(p);
                // Variant after restart.
                Field f = CpsTransformer.class.getDeclaredField("iota");
                f.setAccessible(true);
                ((AtomicLong) f.get(null)).set(0); // simulate VM restart
                p.setDefinition(new CpsFlowDefinition("@Library('test@master') _; import p.MyTest; import p.MyOtherTest; semaphore 'wait'; evaluate 'class MyTestExtended extends p.MyTest {def mytestfunction() {}}; true'", true));
                SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
                ((AtomicLong) f.get(null)).set(0);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b2 = p.getLastBuild();
                assertEquals(2, b2.getNumber());
                SemaphoreStep.success("wait/1", null);
                j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        });
    }

    @Test
    void step() throws Throwable {
        extension.then(j -> {
                sampleRepo.init();
                sampleRepo.write("src/pkg/Slow.groovy", "package pkg; class Slow {static void wait(script) {script.semaphore 'wait-class'}}");
                sampleRepo.write("vars/slow.groovy", "def call() {semaphore 'wait-var'}");
                sampleRepo.git("add", "src", "vars");
                sampleRepo.git("commit", "--message=init");
                GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("semaphore 'start'; def lib = library('stuff@master'); echo 'at the beginning'; lib.pkg.Slow.wait(this); echo 'in the middle'; slow(); echo 'at the end'", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("start/1", b);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("start/1", null);
                SemaphoreStep.waitForStart("wait-class/1", b);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait-class/1", null);
                SemaphoreStep.waitForStart("wait-var/1", b);
        });
        extension.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                SemaphoreStep.success("wait-var/1", null);
                j.assertLogContains("at the end", j.waitForCompletion(b));
                assertEquals(1, b.getActions(LibrariesAction.class).size());
        });
    }

}
