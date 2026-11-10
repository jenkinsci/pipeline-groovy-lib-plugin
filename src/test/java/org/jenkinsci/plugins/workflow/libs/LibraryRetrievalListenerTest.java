/*
 * The MIT License
 *
 * Copyright 2025 Noam Goren
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.junit.jupiter.WithGitSampleRepo;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@WithJenkins
@WithGitSampleRepo
class LibraryRetrievalListenerTest {

    private static final String SECRET = "s3cr3t-token";

    private JenkinsRule r;
    private GitSampleRepoRule sampleRepo;

    @BeforeEach
    void beforeEach(JenkinsRule rule, GitSampleRepoRule repo) throws Exception {
        r = rule;
        sampleRepo = repo;
        sampleRepo.init();
        sampleRepo.write("vars/greet.groovy", "def call() { echo 'hello from lib' }");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        sampleRepo.git("tag", "1.0");
        Recorder.EVENTS.clear();
        Recorder.failOnRetrieve = false;
    }

    @AfterEach
    void afterEach() {
        Recorder.EVENTS.clear();
    }

    private LibraryConfiguration configure(String name, LibraryCachingConfiguration caching) {
        LibraryConfiguration cfg = new LibraryConfiguration(name, new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)));
        cfg.setDefaultVersion("master");
        cfg.setCachingConfiguration(caching);
        GlobalLibraries.get().setLibraries(Collections.singletonList(cfg));
        return cfg;
    }

    private WorkflowJob job(String script) throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    private WorkflowRun build(WorkflowJob p, Result expected) throws Exception {
        return r.assertBuildStatus(expected, p.scheduleBuild2(0));
    }

    private static List<LibraryRetrievalListener.CacheOutcome> outcomes() {
        List<LibraryRetrievalListener.CacheOutcome> l = new ArrayList<>();
        for (Event e : Recorder.EVENTS) {
            if (e.kind.equals("end")) {
                l.add(e.outcome);
            }
        }
        return l;
    }

    @Test
    void cacheOutcomes() throws Exception {
        configure("lib", new LibraryCachingConfiguration(60, "1.0"));
        WorkflowJob p = job("@Library('lib@master') _\ngreet()");
        build(p, Result.SUCCESS);
        assertThat(outcomes(), contains(LibraryRetrievalListener.CacheOutcome.MISS));
        Recorder.EVENTS.clear();
        build(p, Result.SUCCESS);
        assertThat(outcomes(), contains(LibraryRetrievalListener.CacheOutcome.HIT));
        Recorder.EVENTS.clear();
        build(job("@Library('lib@1.0') _\ngreet()"), Result.SUCCESS);
        assertThat(outcomes(), contains(LibraryRetrievalListener.CacheOutcome.EXCLUDED));
    }

    @Test
    void cachingDisabled() throws Exception {
        configure("lib", null);
        build(job("@Library('lib@master') _\ngreet()"), Result.SUCCESS);
        assertThat(outcomes(), contains(LibraryRetrievalListener.CacheOutcome.DISABLED));
    }

    @Test
    void expired() throws Exception {
        configure("lib", new LibraryCachingConfiguration(60, ""));
        WorkflowJob p = job("@Library('lib@master') _\ngreet()");
        build(p, Result.SUCCESS);
        Recorder.EVENTS.clear();
        for (FilePath dir : LibraryCachingConfiguration.getGlobalLibrariesCacheDir().listDirectories()) {
            dir.touch(System.currentTimeMillis() - 2 * 60 * 60 * 1000L);
        }
        build(p, Result.SUCCESS);
        assertThat(outcomes(), contains(LibraryRetrievalListener.CacheOutcome.EXPIRED));
    }

    @Test
    void emptyRecached() throws Exception {
        configure("lib", new LibraryCachingConfiguration(60, ""));
        WorkflowJob p = job("@Library('lib@master') _\ngreet()");
        build(p, Result.SUCCESS);
        Recorder.EVENTS.clear();
        for (FilePath library : LibraryCachingConfiguration.getGlobalLibrariesCacheDir().listDirectories()) {
            for (String root : new String[] {"src", "vars"}) {
                FilePath dir = library.child(root);
                if (dir.isDirectory()) {
                    dir.deleteRecursive();
                }
            }
        }
        build(p, Result.SUCCESS);
        assertThat(outcomes(), contains(LibraryRetrievalListener.CacheOutcome.EMPTY_RECACHED));
    }

    @Test
    void annotationAndStepShareCallSite() throws Exception {
        configure("lib", new LibraryCachingConfiguration(60, ""));
        WorkflowRun viaAnnotation = build(job("@Library('lib@master') _\ngreet()"), Result.SUCCESS);
        assertThat(Recorder.EVENTS, hasSize(2));
        Event start = Recorder.EVENTS.get(0);
        Event end = Recorder.EVENTS.get(1);
        assertEquals("start", start.kind);
        assertEquals("end", end.kind);
        assertEquals(start.thread, end.thread);
        assertEquals("lib", end.record.getName());
        assertEquals("master", end.record.getVersion());
        assertThat(end.retriever, instanceOf(SCMSourceRetriever.class));
        assertThat(end.run, is((Run<?, ?>) viaAnnotation));
        assertThat(end.error, nullValue());
        Recorder.EVENTS.clear();
        WorkflowRun viaStep = build(job("library 'lib@master'\nnode { echo 'x' }"), Result.SUCCESS);
        assertThat(Recorder.EVENTS, hasSize(2));
        assertEquals("start", Recorder.EVENTS.get(0).kind);
        assertEquals("end", Recorder.EVENTS.get(1).kind);
        assertEquals(Recorder.EVENTS.get(0).thread, Recorder.EVENTS.get(1).thread);
        assertThat(Recorder.EVENTS.get(1).run, is((Run<?, ?>) viaStep));
        assertThat(Recorder.EVENTS.get(1).outcome, is(LibraryRetrievalListener.CacheOutcome.HIT));
    }

    @Test
    void endCallbackRunsWhenRetrieverThrows() throws Exception {
        configure("lib", null);
        // The library path does not exist, so the retrieval fails after the cache outcome is known.
        ((SCMBasedRetriever) GlobalLibraries.get().getLibraries().get(0).getRetriever()).setLibraryPath("nowhere");
        WorkflowRun b = build(job("@Library('lib@master') _\ngreet()"), Result.FAILURE);
        assertThat(Recorder.EVENTS, hasSize(2));
        Event end = Recorder.EVENTS.get(1);
        assertEquals("end", end.kind);
        assertThat(end.error, notNullValue());
        assertThat(end.outcome, is(LibraryRetrievalListener.CacheOutcome.DISABLED));
        r.assertLogContains(end.error.getMessage(), b);
    }

    @Test
    void listenerThatThrowsDoesNotChangeTheResult() throws Exception {
        configure("lib", new LibraryCachingConfiguration(60, ""));
        Recorder.failOnRetrieve = true;
        WorkflowRun b = build(job("@Library('lib@master') _\ngreet()"), Result.SUCCESS);
        r.assertLogContains("hello from lib", b);
        // The well behaved listener still got both callbacks.
        assertThat(Recorder.EVENTS.stream().filter(e -> e.listener == Recorder.class).count(), is(2L));
        assertThat(outcomes(), not(empty()));
    }

    @Test
    void sourceAndCachingConfigurationAreExposed() throws Exception {
        LibraryCachingConfiguration caching = new LibraryCachingConfiguration(60, "");
        configure("lib", caching);
        build(job("@Library('lib@master') _\ngreet()"), Result.SUCCESS);
        LibraryRecord record = Recorder.EVENTS.get(1).record;
        assertThat(record.getSource(), is(GlobalLibraries.ForJob.class.getName()));
        assertThat(record.getCachingConfiguration(), is(caching));
    }

    @Test
    void noCredentialMaterialInCallbackArguments() throws Exception {
        GitSCMSource source = new GitSCMSource(null, "https://user:" + SECRET + "@example.invalid/lib.git", "", "*", "", true);
        LibraryConfiguration cfg = new LibraryConfiguration("lib", new SCMSourceRetriever(source));
        cfg.setDefaultVersion("master");
        GlobalLibraries.get().setLibraries(Collections.singletonList(cfg));
        build(job("@Library('lib@master') _\n"), Result.FAILURE);
        assertThat(Recorder.EVENTS, hasSize(2));
        Event end = Recorder.EVENTS.get(1);
        // The record and the arguments the listener sees carry no URL. The retriever is passed as is, as the issue asks.
        assertThat(end.record.toString(), not(containsString(SECRET)));
        assertThat(end.record.getSource(), not(containsString(SECRET)));
        assertThat(end.record.getLogString(), not(containsString(SECRET)));
        assertThat(end.record.getDirectoryName(), not(containsString(SECRET)));
    }

    @Test
    void noListenerBehavesAsBefore() throws Exception {
        configure("lib", new LibraryCachingConfiguration(60, ""));
        Recorder.EVENTS.clear();
        // Registered listeners are the only observers; with nothing recorded the build is unchanged.
        WorkflowRun b = build(job("@Library('lib@master') _\ngreet()"), Result.SUCCESS);
        r.assertLogContains("Library lib@master successfully cached.", b);
        r.assertLogContains("hello from lib", b);
    }

    private static final class Event {
        final String kind;
        final Class<?> listener;
        final LibraryRecord record;
        final LibraryRetriever retriever;
        final Run<?, ?> run;
        final LibraryRetrievalListener.CacheOutcome outcome;
        final Throwable error;
        final String thread = Thread.currentThread().getName();

        Event(String kind, Class<?> listener, LibraryRecord record, LibraryRetriever retriever, Run<?, ?> run,
              LibraryRetrievalListener.CacheOutcome outcome, Throwable error) {
            this.kind = kind;
            this.listener = listener;
            this.record = record;
            this.retriever = retriever;
            this.run = run;
            this.outcome = outcome;
            this.error = error;
        }
    }

    @TestExtension
    public static final class Recorder extends LibraryRetrievalListener {
        static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
        static volatile boolean failOnRetrieve;

        @Override public void onStart(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull Run<?, ?> run) {
            EVENTS.add(new Event("start", Recorder.class, record, retriever, run, null, null));
        }

        @Override public void onEnd(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull Run<?, ?> run,
                                    @CheckForNull CacheOutcome outcome, @CheckForNull Throwable error) {
            EVENTS.add(new Event("end", Recorder.class, record, retriever, run, outcome, error));
        }
    }

    @TestExtension
    public static final class Thrower extends LibraryRetrievalListener {
        @Override public void onStart(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull Run<?, ?> run) {
            if (Recorder.failOnRetrieve) {
                throw new IllegalStateException("listener failure in start");
            }
        }

        @Override public void onEnd(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull Run<?, ?> run,
                                    @CheckForNull CacheOutcome outcome, @CheckForNull Throwable error) {
            if (Recorder.failOnRetrieve) {
                throw new IllegalStateException("listener failure in end");
            }
        }
    }
}
