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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.slaves.WorkspaceList;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.junit.jupiter.WithGitSampleRepo;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import static hudson.ExtensionList.lookupSingleton;
import hudson.plugins.git.extensions.impl.CloneOption;
import jenkins.plugins.git.traits.CloneOptionTrait;
import jenkins.plugins.git.traits.RefSpecsSCMSourceTrait;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.SingleFileSCM;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.WithoutJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.jenkinsci.plugins.workflow.libs.SCMBasedRetriever.PROHIBITED_DOUBLE_DOT;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.AutoClose;

import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.FlagExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WithGitSampleRepo
class SCMSourceRetrieverTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    private JenkinsRule r;
    private GitSampleRepoRule sampleRepo;
    @TempDir
    public Path tempFolder;
    @RegisterExtension
    private final FlagExtension<Boolean> includeSrcTest = new FlagExtension<>(() -> SCMBasedRetriever.INCLUDE_SRC_TEST_IN_LIBRARIES, x -> SCMBasedRetriever.INCLUDE_SRC_TEST_IN_LIBRARIES = x);
    @RegisterExtension
    private final FlagExtension<String> rootProp = FlagExtension.systemProperty(SCMBasedRetriever.ROOT_PROP);
    @AutoClose
    private final LogRecorder logging = new LogRecorder().record(SCMBasedRetriever.class, Level.FINE);

    @BeforeEach
    void beforeEach(JenkinsRule rule, GitSampleRepoRule repo) {
        r = rule;
        sampleRepo = repo;
    }

    @Issue("JENKINS-40408")
    @Test
    void lease() throws Exception {
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
    @Test
    void includeChanges() throws Exception {
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
    @Test
    void dontIncludeChanges() throws Exception {
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
    @Test
    void libraryPath() throws Exception {
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
    @Test
    void libraryPathSecurity() throws Exception {
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
    @Test
    void libraryPathMatcher() {
        assertThat("..", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("./..", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("../foo", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("foo/../bar", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat(".\\..", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("..\\foo", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("foo\\..\\bar", matchesPattern(PROHIBITED_DOUBLE_DOT));
        assertThat("x\u2028/../../../../../foo/", matchesPattern(PROHIBITED_DOUBLE_DOT));
    }

    @Issue("SECURITY-3796")
    @Test
    void libraryAbsolutePathsAreRejected() throws Exception {
        sampleRepo.init();
        sampleRepo.write("sub/path/vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("root_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("/user/jenkins/foo/");
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('root_sub_path@master') import myecho; myecho()", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("Library path must be a relative path", b);
    }

    @Issue("SECURITY-3796")
    @Test
    void symlinksInLibraryPath() throws Exception {
        symlinksInLibraryPath(false);
    }

    @Issue("SECURITY-3796")
    @Test
    void symlinksInLibraryPathWithClone() throws Exception {
        symlinksInLibraryPath(true);
    }

    private void symlinksInLibraryPath(boolean clone) throws Exception {
        assumeSymlinkSupport();
        assumeGitSymlinkSupport();

        // setup a folder that an attacker can link to
        Path tempRoot = tempFolder;
        Path victim = Files.createDirectory(tempRoot.resolve("victim"));
        Path resources = Files.createDirectory(victim.resolve("resources"));
        Path src = Files.createDirectory(victim.resolve("src"));

        Files.writeString(resources.resolve("oops.txt"), "OOPS!");
        Files.writeString(src.resolve("whatever.groovy"), "// blank file");

        sampleRepo.init();
        Path sub = sampleRepo.getRoot().toPath().resolve("sub");
        Files.createDirectory(sub);
        Files.createSymbolicLink(sub.resolve("path"), tempRoot);
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=symlink");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("symlink_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("sub/path/victim/");
        scm.setClone(clone);

        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob wf = r.jenkins.createProject(WorkflowJob.class, "p");
        wf.setDefinition(new CpsFlowDefinition("""
                @Library('symlink_sub_path@master')
                def oops = libraryResource('oops.txt')
                echo oops
                """));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, wf.scheduleBuild2(0));
        r.assertLogContains("Rejecting library: symlink found: sub" + File.separatorChar + "path", b);
    }

    @Issue("SECURITY-3796??")
    @Test
    void symlinksInVars() throws Exception {
        symlinksInVars(false);
    }

    @Issue("SECURITY-3796??")
    @Test
    void symlinksInVarsWithClone() throws Exception {
        symlinksInVars(true);
    }

    private void symlinksInVars(boolean clone) throws Exception {
        assumeSymlinkSupport();
        assumeGitSymlinkSupport();

        // setup a folder that an attacker can link to
        Path tempRoot = tempFolder;
        Path victimDir = Files.createDirectory(tempRoot.resolve("victim"));
        Path victim = Files.writeString(victimDir.resolve("oops.txt"), "OOPS!");

        sampleRepo.init();
        sampleRepo.write("src/whatever.groovy", "// blank file");

        Path resources = Files.createDirectory(sampleRepo.getRoot().toPath().resolve("resources"));
        Files.createSymbolicLink(resources.resolve("link"), victim);
        sampleRepo.git("add", "src", "resources");
        sampleRepo.git("commit", "--message=symlink-attack");

        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("symlink_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("");
        scm.setClone(clone);

        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob wf = r.jenkins.createProject(WorkflowJob.class, "p");
        wf.setDefinition(new CpsFlowDefinition("""
                @Library('symlink_sub_path@master')
                def oops = libraryResource('oops.txt')
                echo oops
                """));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, wf.scheduleBuild2(0));
        r.assertLogContains("Rejecting library: symlink found: resources" + File.separatorChar + "link", b);
    }


    /**
     * Check that symlinks can be created, if not abort with an AssumptionError
     */
    private void assumeSymlinkSupport() {
        try {
            Path p = Files.createDirectory(tempFolder.resolve("symlink-test"));
            Files.createSymbolicLink(p.resolve("link"), p.resolve("target"));
        } catch (IOException e) {
            assumeTrue(false, "Symlinks are not supported: " + e);
        }
    }

    /**
     * Check that Git supports symlinks (Unix like platforms do, Windows platforms need to be opted in).
     */
    private void assumeGitSymlinkSupport() {
        if (!Functions.isWindows()) {
            // assume symlinks are always supported on non windows platforms
            return;
        }
        try {
            // TODO add a method in GitSampleRepoRule to run a git command and return the output
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("git", "config", "get", "--type=bool", "core.symlinks");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) {
                byte[] data = is.readAllBytes();
                String output = new String(data, Charset.defaultCharset()).trim();
                // we have normalized git's output with the `--type` above to force a known response
                // does not handle localization however this will fail to assuming false so will not cause breakage
                assumeTrue("true".equals(output), "Git symlinks support (core.symlinks) is not configured");
            }
        } catch (Exception e) {
            assumeTrue(false, "Could not determine if Git is not currently configured to support symlinks: " + e);
        }
    }

    @Issue("JENKINS-43802")
    @Test
    void owner() throws Exception {
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("test", new SCMSourceRetriever(new NeedsOwnerSCMSource()))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('test@abc123') import libVersion; echo(/loaded lib #${libVersion()}/)", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("loaded lib #abc123", b);
        r.assertLogContains("Running in retrieve from p", b);
    }

    public static final class NeedsOwnerSCMSource extends SCMSource {

        @Override
        protected SCMRevision retrieve(String version, TaskListener listener, Item context) throws IOException, InterruptedException {
            if (context == null) {
                throw new AbortException("No context in retrieve!");
            } else {
                listener.getLogger().println("Running in retrieve from " + context.getFullName());
            }
            return new DummySCMRevision(version, new SCMHead("trunk"));
        }

        @Override
        public SCM build(SCMHead head, SCMRevision revision) {
            String version = ((DummySCMRevision) revision).version;
            return new SingleFileSCM("vars/libVersion.groovy", ("def call() {'" + version + "'}").getBytes());
        }

        private static final class DummySCMRevision extends SCMRevision {
            private final String version;

            DummySCMRevision(String version, SCMHead head) {
                super(head);
                this.version = version;
            }

            @Override
            public boolean equals(Object obj) {
                return obj instanceof DummySCMRevision && version.equals(((DummySCMRevision) obj).version);
            }

            @Override
            public int hashCode() {
                return version.hashCode();
            }
        }

        @Override
        protected void retrieve(SCMSourceCriteria criteria, SCMHeadObserver observer, SCMHeadEvent<?> event, TaskListener listener) throws IOException, InterruptedException {
            throw new IOException("not implemented");
        }

        @TestExtension("owner")
        public static final class DescriptorImpl extends SCMSourceDescriptor {}
    }

    @Test
    void retry() throws Exception {
        WorkflowRun b = prepareRetryTests(new FailingSCMSource());
        r.assertLogContains("Failing 'checkout' on purpose!", b);
        r.assertLogContains("Retrying after 10 seconds", b);
    }

    @Test
    void retryDuringFetch() throws Exception {
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
    void modernAndLegacyImpls() {
        SCMSourceRetriever.DescriptorImpl modern = lookupSingleton(SCMSourceRetriever.DescriptorImpl.class);

        containsInAnyOrder(modern.getSCMDescriptors(), contains(instanceOf(FakeModernSCM.DescriptorImpl.class)));
        containsInAnyOrder(modern.getSCMDescriptors(), contains(instanceOf(FakeAlsoModernSCM.DescriptorImpl.class)));
        containsInAnyOrder(modern.getSCMDescriptors(), not(contains(instanceOf(BasicSCMSource.DescriptorImpl.class))));
    }

    // Implementation of latest and greatest API
    public static final class FakeModernSCM extends SCMSource {

        @Override
        protected void retrieve(SCMSourceCriteria c, @NonNull SCMHeadObserver o, SCMHeadEvent<?> e, @NonNull TaskListener l) {}

        @Override
        public @NonNull SCM build(@NonNull SCMHead head, SCMRevision revision) { return null; }

        @TestExtension("modernAndLegacyImpls")
        public static final class DescriptorImpl extends SCMSourceDescriptor {}

        @Override
        protected SCMRevision retrieve(@NonNull String thingName, @NonNull TaskListener listener, Item context) throws IOException, InterruptedException {
            return super.retrieve(thingName, listener, context);
        }
    }

    // Implementation of second latest and second greatest API
    public static final class FakeAlsoModernSCM extends SCMSource {

        @Override
        protected void retrieve(SCMSourceCriteria c, @NonNull SCMHeadObserver o, SCMHeadEvent<?> e, @NonNull TaskListener l) {}

        @Override
        public @NonNull SCM build(@NonNull SCMHead head, SCMRevision revision) { return null; }

        @TestExtension("modernAndLegacyImpls")
        public static final class DescriptorImpl extends SCMSourceDescriptor {}

        @Override
        protected SCMRevision retrieve(@NonNull String thingName, @NonNull TaskListener listener) throws IOException, InterruptedException {
            return super.retrieve(thingName, listener);
        }
    }

    // No modern stuff
    public static class BasicSCMSource extends SCMSource {

        @Override
        protected void retrieve(SCMSourceCriteria c, @NonNull SCMHeadObserver o, SCMHeadEvent<?> e, @NonNull TaskListener l) {}

        @Override
        public @NonNull SCM build(@NonNull SCMHead head, SCMRevision revision) { return null; }

        @TestExtension("modernAndLegacyImpls")
        public static final class DescriptorImpl extends SCMSourceDescriptor {}
    }

    @Issue("JENKINS-66629")
    @Test
    void renameDeletesOldLibsWorkspace() throws Exception {
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
    @Test
    void deleteRemovesLibsWorkspace() throws Exception {
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

    @Test
    void cloneMode() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.write("README.md", "Summary");
        sampleRepo.git("rm", "file");
        sampleRepo.git("add", ".");
        sampleRepo.git("commit", "--message=init");
        GitSCMSource src = new GitSCMSource(sampleRepo.toString());
        CloneOption cloneOption = new CloneOption(true, true, null, null);
        cloneOption.setHonorRefspec(true);
        src.setTraits(List.of(new CloneOptionTrait(cloneOption), new RefSpecsSCMSourceTrait("+refs/heads/master:refs/remotes/origin/master")));
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

    @Test
    void cloneModeLibraryPath() throws Exception {
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

    @Test
    void cloneModeLibraryPathSecurity() throws Exception {
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

    @Issue("SECURITY-3796")
    @Test
    void cloneModeLibraryAbsolutePathsAreRejected() throws Exception {
        sampleRepo.init();
        sampleRepo.write("sub/path/vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "sub");
        sampleRepo.git("commit", "--message=init");
        SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString()));
        LibraryConfiguration lc = new LibraryConfiguration("root_sub_path", scm);
        lc.setIncludeInChangesets(false);
        scm.setLibraryPath("/user/jenkins/foo/");
        scm.setClone(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('root_sub_path@master') import myecho; myecho()", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("Library path must be a relative path", b);
    }

    @Issue("SECURITY-3796")
    @WithoutJenkins
    @Test
    void relativePath() {
        assertTrue(SCMBasedRetriever.isRelativePath("foo/bar"));
        assertFalse(SCMBasedRetriever.isRelativePath("/foo/bar"));
        if (Functions.isWindows()) {
            assertFalse(SCMBasedRetriever.isRelativePath("\\foo\\bar"));
            assertFalse(SCMBasedRetriever.isRelativePath("x:\\wibble\\bar"));
            assertFalse(SCMBasedRetriever.isRelativePath("x:/foo/manchu"));
            assertFalse(SCMBasedRetriever.isRelativePath("\\\\server\\share\\somepath\\"));
            assertFalse(SCMBasedRetriever.isRelativePath("//server/share/somepath/"));
            // drive relative paths
            assertFalse(SCMBasedRetriever.isRelativePath("x:wibble"));
            // drive relative with no path
            assertFalse(SCMBasedRetriever.isRelativePath("z:"));
        }
    }

    @Test
    void cloneModeExcludeSrcTest() throws Exception {
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

    @Test
    void cloneModeIncludeSrcTest() throws Exception {
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

    @Test
    void nonWorkspaceRoot() throws Exception {
        var libs = tempFolder.resolve("libs");
        System.setProperty(SCMBasedRetriever.ROOT_PROP, libs.toString());
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", ".");
        sampleRepo.git("commit", "--message=init");
        var src = new GitSCMSource(sampleRepo.toString());
        var lc = new LibraryConfiguration("echoing", new SCMSourceRetriever(src));
        lc.setDefaultVersion("master");
        GlobalUntrustedLibraries.get().setLibraries(List.of(lc));
        var sharedDirName = new LibraryRecord("echoing", "master", false, true, null, GlobalUntrustedLibraries.ForJob.class.getName(), null).getDirectoryName();

        // @Library annotation uses ROOT_PROP — two jobs share the same checkout dir
        var p1 = r.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("@Library('echoing@master') _; myecho()", true));
        var p2 = r.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition("@Library('echoing@master') _; myecho()", true));
        r.buildAndAssertSuccess(p1);
        r.buildAndAssertSuccess(p2);

        // library() step without retriever resolves the same config → same ROOT_PROP dir, no new entry
        var p3 = r.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition("library('echoing@master'); myecho()", true));
        r.buildAndAssertSuccess(p3);
        r.assertLogContains("something special", p3.getLastBuild());

        // library() step with explicit retriever has a per-build source → ROOT_PROP must be ignored
        var p4 = r.jenkins.createProject(WorkflowJob.class, "p4");
        p4.setDefinition(new CpsFlowDefinition(
            "library(identifier: 'echoing@master', retriever: modernSCM(gitSource('" + sampleRepo + "')))\nmyecho()", true));
        r.buildAndAssertSuccess(p4);
        r.assertLogContains("something special", p4.getLastBuild());

        try (var s = Files.list(libs)) {
            assertThat(s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList(), contains(sharedDirName));
        }
        FilePath p4libs = r.jenkins.getWorkspaceFor(p4).withSuffix("@libs");
        assertThat(p4libs.getParent().list(), contains(p4libs));
    }

    // FIFOs cannot be committed to git, so we test rejectSpecialFiles directly against the working directory
    @Test
    void fifoInLibRejected() throws Exception {
        assumeFalse(Functions.isWindows(), "FIFOs are not supported on windows");
        sampleRepo.init();
        sampleRepo.write("vars/hello.groovy", "def call() {}");
        Runtime.getRuntime().exec(new String[]{"mkfifo", new File(sampleRepo.getRoot(), "vars/pipe.txt").toString()}).waitFor();
        assertThat(assertThrows(AbortException.class,
            () -> SCMBasedRetriever.rejectSpecialFiles(new FilePath(sampleRepo.getRoot())))
            .getMessage(), containsString("non-regular file found"));
    }

    @Test
    void symlinkInVarsRejected() throws Exception {
        assumeFalse(Functions.isWindows(), "symlinks require special privileges on windows");
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "master");
        java.nio.file.Files.createSymbolicLink(
            new File(sampleRepo.getRoot(), "vars/leak.txt").toPath(),
            new File("/etc/passwd").toPath());
        sampleRepo.git("add", "vars/leak.txt");
        sampleRepo.git("commit", "--message=add-symlink");
        for (boolean clone : new boolean[] {false, true}) {
            SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
            scm.setClone(clone);
            GlobalLibraries.get().setLibraries(Collections.singletonList(
                new LibraryConfiguration("symlink_lib", scm)));
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p" + clone);
            p.setDefinition(new CpsFlowDefinition("@Library('symlink_lib@master') _", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("Rejecting library: symlink found", b);
        }
    }

    @Test
    void symlinkedDirectoryInLibRejected() throws Exception {
        assumeFalse(Functions.isWindows(), "symlinks require special privileges on windows");
        sampleRepo.init();
        sampleRepo.write("src/org/foo/Lib.groovy", "class Lib {}");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("checkout", "master");
        // Replace vars/ with a symlink to an arbitrary directory
        java.nio.file.Files.createSymbolicLink(
            new File(sampleRepo.getRoot(), "vars").toPath(),
            new File("/etc").toPath());
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=add-symlinked-dir");
        for (boolean clone : new boolean[] {false, true}) {
            SCMSourceRetriever scm = new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true));
            scm.setClone(clone);
            GlobalLibraries.get().setLibraries(Collections.singletonList(
                new LibraryConfiguration("symdir_lib", scm)));
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "pd" + clone);
            p.setDefinition(new CpsFlowDefinition("@Library('symdir_lib@master') _", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("Rejecting library: symlink found", b);
        }
    }

}
