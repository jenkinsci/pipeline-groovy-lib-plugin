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

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.ChangeLogSet;
import hudson.slaves.WorkspaceList;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.cps.global.GrapeTest;
import org.jenkinsci.plugins.workflow.cps.global.UserDefinedGlobalVariable;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.recipes.LocalData;

public class LibraryAdderTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();
    @Rule public GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();

    @Test public void smokes() throws Exception {
        sampleRepo.init();
        String lib = "package pkg; class Lib {static String CONST = 'constant'}";
        sampleRepo.write("src/pkg/Lib.groovy", lib);
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        String script = "@Library('stuff@master') import static pkg.Lib.*; echo(/using ${CONST}/)";
        p.setDefinition(new CpsFlowDefinition(script, true));
        r.assertLogContains("using constant", r.buildAndAssertSuccess(p));
        sampleRepo.git("tag", "1.0");
        sampleRepo.write("src/pkg/Lib.groovy", lib.replace("constant", "modified"));
        sampleRepo.git("commit", "--all", "--message=modified");
        r.assertLogContains("using modified", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition(script.replace("master", "1.0"), true));
        r.assertLogContains("using constant", r.buildAndAssertSuccess(p));
    }

    @Test public void usingInterpolation() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/pkg/Lib.groovy", "package pkg; class Lib {static String CONST = 'initial'}");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("tag", "initial");
        sampleRepo.write("src/pkg/Lib.groovy", "package pkg; class Lib {static String CONST = 'modified'}");
        sampleRepo.git("commit", "--all", "--message=modified");
        LibraryConfiguration stuff = new LibraryConfiguration("stuff",
            new SCMRetriever(
                    new GitSCM(Collections.singletonList(new UserRemoteConfig(sampleRepo.fileUrl(), null, null, null)),
                            Collections.singletonList(new BranchSpec("${library.stuff.version}")),
                            null, null, Collections.emptyList())));
        stuff.setDefaultVersion("master");
        stuff.setImplicit(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(stuff));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('stuff@master') import pkg.Lib; echo(/using ${Lib.CONST}/)", true));
        r.assertLogContains("using modified", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("@Library('stuff@initial') import pkg.Lib; echo(/using ${Lib.CONST}/)", true));
        r.assertLogContains("using initial", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("@Library('stuff') import pkg.Lib; echo(/using ${Lib.CONST}/)", true));
        r.assertLogContains("using modified", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("echo(/using ${pkg.Lib.CONST}/)", true));
        r.assertLogContains("using modified", r.buildAndAssertSuccess(p));
    }

    @Issue("JENKINS-41497")
    @Test public void dontIncludeChangesetsOverriden() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration lc = new LibraryConfiguration("dont_include_changes", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        lc.setIncludeInChangesets(false);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library(value='dont_include_changes@master', changelog=true) import myecho; myecho()", true));
        FilePath base = r.jenkins.getWorkspaceFor(p).withSuffix("@libs").child("dont_include_changes");
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
        }
    }

    @Issue("JENKINS-41497")
    @Test public void includeChangesetsOverridden() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration lc = new LibraryConfiguration("include_changes", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        lc.setIncludeInChangesets(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(lc));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library(value='include_changes@master', changelog=false) import myecho; myecho()", true));
        FilePath base = r.jenkins.getWorkspaceFor(p).withSuffix("@libs").child("include_changes");
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
        }
    }

    @Test public void globalVariable() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/myecho.groovy", "def call() {echo 'something special'}");
        sampleRepo.write("vars/myecho.txt", "Says something very special!");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("echo-utils",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('echo-utils@master') import myecho; myecho()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("something special", b);
        GlobalVariable var = GlobalVariable.byName("myecho", b);
        assertNotNull(var);
        assertEquals("Says something very special!", ((UserDefinedGlobalVariable) var).getHelpHtml());
    }

    @Test public void dynamicLibraries() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/pkg/Lib.groovy", "package pkg; class Lib {static String CONST = 'constant'}");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        DynamicResolver.remote = sampleRepo.toString();
        p.setDefinition(new CpsFlowDefinition("@Library('dynamic') import pkg.Lib; echo(/using ${Lib.CONST}/)", true));
        r.assertLogContains("using constant", r.buildAndAssertSuccess(p));
    }
    @TestExtension("dynamicLibraries") public static class DynamicResolver extends LibraryResolver {
        @Override public boolean isTrusted() {
            return false;
        }
        static String remote;
        @Override public Collection<LibraryConfiguration> forJob(Job<?,?> job, Map<String,String> libraryVersions) {
            if (libraryVersions.containsKey("dynamic")) {
                LibraryConfiguration cfg = new LibraryConfiguration("dynamic", new SCMSourceRetriever(new GitSCMSource(null, remote, "", "*", "", true)));
                cfg.setDefaultVersion("master");
                return Collections.singleton(cfg);
            } else {
                return Collections.emptySet();
            }
        }
    }

    @Test public void undefinedLibraries() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('nonexistent') _", true));
        r.assertLogContains(Messages.LibraryDecorator_could_not_find_any_definition_of_librari(Collections.singletonList("nonexistent")), r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0)));
    }

    /** @see GrapeTest */
    @Test public void grape() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/semver/Version.groovy",
            "package semver\n" +
            "@Grab('com.vdurmont:semver4j:2.0.1') import com.vdurmont.semver4j.Semver\n" + // https://github.com/vdurmont/semver4j#using-gradle
            "public class Version implements Serializable {\n" +
            "  private final String v\n" +
            "  public Version(String v) {this.v = v}\n" +
            // @NonCPS since third-party class is not Serializable
            "  @NonCPS public boolean isGreaterThan(String version) {\n" +
            "    new Semver(v).isGreaterThan(version)\n" +
            "  }\n" +
            "}");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("semver", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "@Library('semver@master') import semver.Version\n" +
            "echo(/1.2.0 > 1.0.0? ${new Version('1.2.0').isGreaterThan('1.0.0')}/)\n" +
            "echo(/1.0.0 > 1.2.0? ${new Version('1.0.0').isGreaterThan('1.2.0')}/)", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("1.2.0 > 1.0.0? true", b);
        r.assertLogContains("1.0.0 > 1.2.0? false", b);
    }

    @Test public void noReplayTrustedLibraries() throws Exception {
        sampleRepo.init();
        String originalMessage = "must not be edited";
        String originalScript = "def call() {echo '" + originalMessage + "'}";
        sampleRepo.write("vars/trusted.groovy", originalScript);
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("trusted", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('trusted@master') import trusted; trusted()", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p);
        r.assertLogContains(originalMessage, b1);
        ReplayAction ra = b1.getAction(ReplayAction.class);
        assertEquals(Collections.emptyMap(), ra.getOriginalLoadedScripts());
        WorkflowRun b2 = (WorkflowRun) ra.run(ra.getOriginalScript(), Collections.singletonMap("trusted", originalScript.replace(originalMessage, "should not allowed"))).get();
        r.assertBuildStatusSuccess(b2); // currently do not throw an error, since the GUI does not offer it anyway
        r.assertLogContains(originalMessage, b2);
    }

    @Issue({"JENKINS-38021", "JENKINS-31484"})
    @Test public void gettersAndSetters() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/config.groovy", "class config implements Serializable {private String foo; public String getFoo() {return(/loaded ${this.foo}/)}; public void setFoo(String value) {this.foo = value.toUpperCase()}}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("config",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('config@master') _; timeout(1) {config.foo = 'bar'; echo(/set to $config.foo/)}", false));
        r.assertLogContains("set to loaded BAR", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("@Library('config@master') _; config.setFoo('bar'); echo(/set to ${config.getFoo()}/)", true));
        r.assertLogContains("set to loaded BAR", r.buildAndAssertSuccess(p));
        p.setDefinition(new CpsFlowDefinition("@Library('config@master') _; echo(/set to $config.foo/)", true));
        r.assertLogContains("set to loaded null", r.buildAndAssertSuccess(p));
    }

    @Issue("JENKINS-56682")
    @Test public void scriptFieldsWhereInitializerUsesLibrary() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/pkg/Foo.groovy", "package pkg; class Foo { }");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("lib",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "@Library('lib@master') import pkg.Foo\n" +
                "import groovy.transform.Field\n" +
                "@Field f = new Foo()\n" +
                "@Field static g = new Foo()\n", true));
        r.buildAndAssertSuccess(p);
    }

    @Test public void srcTestNotOnClassPath() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/test/Foo.groovy", "package test; class Foo { }");
        sampleRepo.write("src/test/foo/Bar.groovy", "package test.foo; class Bar { }");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
            new LibraryConfiguration("lib",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@Library('lib@master') import test.Foo", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("Excluding src/test/ from checkout", b);
        r.assertLogContains("expected to contain at least one of src or vars directories", b);
    }

    @Issue("SECURITY-2422")
    @Test public void libraryNamesAreNotUsedAsBuildDirectoryPaths() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/globalLibVar.groovy", "def call() { echo('global library') }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo2.init();
        LibraryConfiguration globalLib = new LibraryConfiguration("global",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        globalLib.setDefaultVersion("master");
        globalLib.setImplicit(true);
        GlobalLibraries.get().setLibraries(Collections.singletonList(globalLib));
        // Create a folder library with distinct name, but which if used as a path will match the libs directory for the global library
        sampleRepo2.write("vars/folderLibVar.groovy", "def call() { jenkins.model.Jenkins.get().setSystemMessage('folder library') }");
        sampleRepo2.git("add", "vars");
        sampleRepo2.git("commit", "--message=init");
        LibraryConfiguration folderLib = new LibraryConfiguration("folder/../global",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo2.toString(), "", "*", "", true)));
        folderLib.setDefaultVersion("master");
        folderLib.setImplicit(true);
        Folder f = r.jenkins.createProject(Folder.class, "folder1");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(folderLib)));
        // Create a build that uses both libraries.
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("globalLibVar(); folderLibVar()", true));
        // The contents of the folder library should be untrusted and in a distinct libs directory.
        WorkflowRun b = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("Scripts not permitted to use staticMethod jenkins.model.Jenkins get", b);
        assertThat(r.jenkins.getSystemMessage(), nullValue());
    }

    @Issue("SECURITY-2586")
    @Test public void libraryNamesAreNotUsedAsCacheDirectories() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/globalLibVar.groovy", "def call() { echo('global library') }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo2.init();
        LibraryConfiguration globalLib = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        globalLib.setDefaultVersion("master");
        globalLib.setImplicit(true);
        globalLib.setCachingConfiguration(new LibraryCachingConfiguration(60, ""));
        GlobalLibraries.get().setLibraries(Collections.singletonList(globalLib));
        // Create a folder library with the same name and which is also set up to enable caching.
        sampleRepo2.write("vars/folderLibVar.groovy", "def call() { jenkins.model.Jenkins.get().setSystemMessage('folder library') }");
        sampleRepo2.git("add", "vars");
        sampleRepo2.git("commit", "--message=init");
        LibraryConfiguration folderLib = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo2.toString(), "", "*", "", true)));
        folderLib.setDefaultVersion("master");
        folderLib.setImplicit(true);
        folderLib.setCachingConfiguration(new LibraryCachingConfiguration(60, ""));
        Folder f = r.jenkins.createProject(Folder.class, "folder1");
        f.getProperties().add(new FolderLibraries(Collections.singletonList(folderLib)));
        // Create a job that uses the folder library, which will take precedence over the global library, since they have the same name.
        WorkflowJob p = f.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("folderLibVar()", true));
        // First build fails as expected since it is not trusted. The folder library gets used and is cached.
        WorkflowRun b1 = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("Only using first definition of library library", b1);
        r.assertLogContains("Scripts not permitted to use staticMethod jenkins.model.Jenkins get", b1);
        // Attacker deletes the folder library, then reruns the build.
        // The global library should not use the cached version of the folder library.
        f.getProperties().clear();
        WorkflowRun b2 = r.buildAndAssertStatus(Result.FAILURE, p);
        r.assertLogContains("No such DSL method 'folderLibVar'", b2);
        assertThat(r.jenkins.getSystemMessage(), nullValue());
    }

    @LocalData
    @Test
    public void correctLibraryDirectoryUsedWhenResumingOldBuild() throws Exception {
        // LocalData was captured after saving the build in the following snippet:
        /*
        sampleRepo.init();
        sampleRepo.write("vars/foo.groovy", "def call() { echo('called Foo') }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(
                new LibraryConfiguration("lib",
                        new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "@Library('lib@master') _\n" +
                "sleep 100\n" +
                "foo()", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        Thread.sleep(2000);
        b.save();
        */
        WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        WorkflowRun b = p.getBuildByNumber(1);
        r.assertBuildStatus(Result.SUCCESS, r.waitForCompletion(b));
        r.assertLogContains("called Foo", b);
    }

    @Issue("JENKINS-66898")
    @Test
    public void parallelBuildsDontInterfereWithExpiredCache() throws Throwable {
        // Add a few files to the library so the deletion is not too fast
        // Before fixing JENKINS-66898 this test was failing almost always
        // with a build failure
        sampleRepo.init();
        sampleRepo.write("vars/foo.groovy", "def call() { echo 'foo' }");
        sampleRepo.write("vars/bar.groovy", "def call() { echo 'bar' }");
        sampleRepo.write("vars/foo2.groovy", "def call() { echo 'foo2' }");
        sampleRepo.write("vars/foo3.groovy", "def call() { echo 'foo3' }");
        sampleRepo.write("vars/foo4.groovy", "def call() { echo 'foo4' }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        LibraryConfiguration config = new LibraryConfiguration("library",
                new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        config.setDefaultVersion("master");
        config.setImplicit(true);
        config.setCachingConfiguration(new LibraryCachingConfiguration(30, null));
        GlobalLibraries.get().getLibraries().add(config);
        WorkflowJob p1 = r.createProject(WorkflowJob.class);
        WorkflowJob p2 = r.createProject(WorkflowJob.class);
        p1.setDefinition(new CpsFlowDefinition("foo()", true));
        p2.setDefinition(new CpsFlowDefinition("foo()", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p1);
        LibrariesAction action = b1.getAction(LibrariesAction.class);
        LibraryRecord record = action.getLibraries().get(0);
        FilePath cache = LibraryCachingConfiguration.getGlobalLibrariesCacheDir().child(record.getDirectoryName());
        //Expire the cache
        long oldMillis = ZonedDateTime.now().minusMinutes(35).toInstant().toEpochMilli();
        cache.touch(oldMillis);
        QueueTaskFuture<WorkflowRun> f1 = p1.scheduleBuild2(0);
        QueueTaskFuture<WorkflowRun> f2 = p2.scheduleBuild2(0);
        r.assertBuildStatus(Result.SUCCESS, f1);
        r.assertBuildStatus(Result.SUCCESS, f2);
        // Disabling these 2 checks as they are flaky
        // Occasionally the second job runs first and then build output doesn't match
        // r.assertLogContains("is due for a refresh after", f1.get());
        // r.assertLogContains("Library library@master is cached. Copying from home.", f2.get());
    }

    @Issue("JENKINS-68544")
    @WithoutJenkins
    @Test public void className() {
        assertThat(LibraryAdder.LoadedLibraries.className("/path/to/lib/src/some/pkg/Type.groovy", "/path/to/lib/src"), is("some.pkg.Type"));
        assertThat(LibraryAdder.LoadedLibraries.className("C:\\path\\to\\lib\\src\\some\\pkg\\Type.groovy", "C:\\path\\to\\lib\\src"), is("some.pkg.Type"));
        assertThat(LibraryAdder.LoadedLibraries.className("C:\\path\\to\\Extra\\lib\\src\\some\\pkg\\Type.groovy", "C:\\path\\to\\Extra\\lib\\src"), is("some.pkg.Type"));
    }

}
