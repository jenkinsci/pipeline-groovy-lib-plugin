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

package org.jenkinsci.plugins.workflow.cps.global;

import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.LibraryRetriever;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

@Issue("JENKINS-26192")
class GrapeTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    @RegisterExtension
    private final JenkinsSessionExtension story = new JenkinsSessionExtension();

    @Test
    void useBinary() throws Throwable {
        story.then(j -> {
                FileUtils.write(new File(libroot(), "src/pkg/Lists.groovy"),
                        """
                                package pkg
                                @Grab('commons-primitives:commons-primitives:1.0')
                                import org.apache.commons.collections.primitives.ArrayIntList
                                static def arrayInt(script) {
                                  script.semaphore 'wait'
                                  new ArrayIntList()
                                }""", StandardCharsets.UTF_8);
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("echo(/got ${pkg.Lists.arrayInt(this)}/)", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
        });
        story.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);
                SemaphoreStep.success("wait/1", null);
                j.assertBuildStatusSuccess(j.waitForCompletion(b));
                j.assertLogContains("got []", b);
        });
    }

    @Test
    void var() throws Throwable {
        story.then(j -> {
                FileUtils.write(new File(libroot(), "vars/one.groovy"),
                        """
                                @Grab('commons-primitives:commons-primitives:1.0')
                                import org.apache.commons.collections.primitives.ArrayIntList
                                def call() {
                                  def list = new ArrayIntList()
                                  list.incrModCount()
                                  list.modCount
                                }""", StandardCharsets.UTF_8);
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("echo(/${one()} + ${one()} = ${one() + one()}/)", true));
                j.assertLogContains("1 + 1 = 2", j.buildAndAssertSuccess(p));
        });
    }

    // TODO test transitive dependencies; need to find something in Central which has a dependency not in this plugin’s test classpath and which could be used easily from a script

    @Test
    void nonexistentLibrary() throws Throwable {
        story.then(j -> {
                FileUtils.write(new File(libroot(), "src/pkg/X.groovy"),
                        """
                                package pkg
                                @Grab('net.nowhere:nonexistent:99.9')
                                static def run() {}""", StandardCharsets.UTF_8);
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("pkg.X.run()", true));
                j.assertLogContains("net.nowhere", j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
        });
    }

    @Test
    void nonexistentImport() throws Throwable {
        story.then(j -> {
                FileUtils.write(new File(libroot(), "src/pkg/X.groovy"),
                        """
                                package pkg
                                @Grab('commons-primitives:commons-primitives:1.0')
                                import net.nowhere.Nonexistent
                                static def run() {new Nonexistent()}""", StandardCharsets.UTF_8);
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("pkg.X.run()", true));
                j.assertLogContains("net.nowhere.Nonexistent", j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
        });
    }

    // TODO test alternate Maven repositories

    @Disabled("TODO MissingMethodException: No signature of method: static com.google.common.base.CharMatcher.whitespace() is applicable for argument types: () values: []")
    @Test
    void overrideCoreLibraries() throws Throwable {
        story.then(j -> {
                FileUtils.write(new File(libroot(), "src/pkg/Strings.groovy"),
                    "package pkg\n" +
                    "@Grab('com.google.guava:guava:19.0')\n" + // 11.0.1 from core has only WHITESPACE constant
                    "import com.google.common.base.CharMatcher\n" +
                    "static def hasWhitespace(text) {\n" +
                    "  CharMatcher.whitespace().matchesAnyOf(text)\n" +
                    "}", StandardCharsets.UTF_8);
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("echo(/checking ${pkg.Strings.hasWhitespace('hello world')}/)", true));
                j.assertLogContains("checking true", j.buildAndAssertSuccess(p));
        });
    }

    @Disabled("TODO fails on CI and inside a Docker container, though for different reasons: `download failed` vs. `/var/maven/.groovy/grapes/resolved-caller-all-caller-working61.xml (No such file or directory)`; and a test-scoped dep on docker-workflow:1.7 does not help")
    @Test
    void useSource() throws Throwable {
        story.then(j -> {
                FileUtils.write(new File(libroot(), "src/pkg/Dokker.groovy"),
                        """
                                package pkg
                                @Grapes([@Grab('org.jenkins-ci.plugins:docker-workflow:1.7'), @Grab('org.jenkins-ci.plugins:docker-commons:1.3.1')])
                                import org.jenkinsci.plugins.docker.workflow.Docker
                                static def stuff(script, body) {
                                  new Docker(script).node {body()}
                                }""", StandardCharsets.UTF_8);
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("pkg.Dokker.stuff(this) {semaphore 'wait'; writeFile file: 'x', text: 'CPS-transformed'; echo(/ran ${readFile 'x'}/)}", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
        });
        story.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);
                SemaphoreStep.success("wait/1", null);
                j.assertBuildStatusSuccess(j.waitForCompletion(b));
                j.assertLogContains("ran CPS-transformed", b);
        });
    }

    private File libroot() {
        File lib = new File(Jenkins.get().getRootDir(), "somelib");
        LibraryConfiguration cfg = new LibraryConfiguration("somelib", new LocalRetriever(lib));
        cfg.setImplicit(true);
        cfg.setDefaultVersion("fixed");
        GlobalLibraries.get().setLibraries(List.of(cfg));
        return lib;
    }

    private static final class LocalRetriever extends LibraryRetriever {
        private final File lib;

        LocalRetriever(File lib) {
            this.lib = lib;
        }

        @Override
        public void retrieve(String name, String version, boolean changelog, FilePath target, Run<?, ?> run, TaskListener listener) throws Exception {
            new FilePath(lib).copyRecursiveTo(target);
        }

        @Override
        public void retrieve(String name, String version, FilePath target, Run<?, ?> run, TaskListener listener) throws Exception {
            retrieve(name, version, false, target, run, listener);
        }
    }

    @Test
    void outsideLibrary() throws Throwable {
        story.then(j -> {
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        """
                                @Grab('commons-primitives:commons-primitives:1.0')
                                import org.apache.commons.collections.primitives.ArrayIntList
                                echo(/got ${new ArrayIntList()}/)""", false));
                j.assertLogContains("got []", j.buildAndAssertSuccess(p));
        });
    }

    @Test
    void outsideLibrarySandbox() throws Throwable {
        story.then(j -> {
                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        """
                                @Grab('commons-primitives:commons-primitives:1.0')
                                import org.apache.commons.collections.primitives.ArrayIntList
                                new ArrayIntList()""", true));
                // Even assuming signature approvals, we do not want to allow Grape to be used from sandboxed scripts.
                ScriptApproval.get().approveSignature("new org.apache.commons.collections.primitives.ArrayIntList");
                j.assertLogContains("Annotation Grab cannot be used in the sandbox", j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
        });
    }
}
