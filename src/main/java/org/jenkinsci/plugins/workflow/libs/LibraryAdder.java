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

import hudson.AbortException;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.cps.GlobalVariableSet;
import org.jenkinsci.plugins.workflow.cps.global.UserDefinedGlobalVariable;
import org.jenkinsci.plugins.workflow.cps.replay.OriginalLoadedScripts;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.flow.FlowCopier;

/**
 * Given {@link LibraryResolver}, actually adds to the Groovy classpath.
 */
@Extension public class LibraryAdder extends ClasspathAdder {

    private static final Logger LOGGER = Logger.getLogger(LibraryAdder.class.getName());
    
    private static ConcurrentHashMap<String, ReentrantReadWriteLock> cacheRetrieveLock = new ConcurrentHashMap<>();

    static @NonNull ReentrantReadWriteLock getReadWriteLockFor(@NonNull String name) {
        return cacheRetrieveLock.computeIfAbsent(name, s -> new ReentrantReadWriteLock(true));
    }
    
    @Override public List<Addition> add(CpsFlowExecution execution, List<String> libraries, HashMap<String, Boolean> changelogs) throws Exception {
        Queue.Executable executable = execution.getOwner().getExecutable();
        Run<?,?> build;
        if (executable instanceof Run) {
            build = (Run) executable;
        } else {
            // SCM.checkout does not make it possible to do checkouts outside the context of a Run.
            return Collections.emptyList();
        }
        // First parse the library declarations (if any) looking for requested versions.
        Map<String,String> libraryVersions = new HashMap<>();
        Map<String,Boolean> libraryChangelogs = new HashMap<>();
        Map<String,String> librariesUnparsed = new HashMap<>();
        for (String library : libraries) {
            String[] parsed = parse(library);
            libraryVersions.put(parsed[0], parsed[1]);
            libraryChangelogs.put(parsed[0], changelogs.get(library));
            librariesUnparsed.put(parsed[0], library);
        }
        List<Addition> additions = new ArrayList<>();
        LibrariesAction action = build.getAction(LibrariesAction.class);
        if (action != null) {
            // Resuming a build, so just look up what we loaded before.
            for (LibraryRecord record : action.getLibraries()) {
                FilePath libDir = new FilePath(execution.getOwner().getRootDir()).child("libs/" + record.getDirectoryName());
                for (String root : new String[] {"src", "vars"}) {
                    FilePath dir = libDir.child(root);
                    if (dir.isDirectory()) {
                        additions.add(new Addition(dir.toURI().toURL(), record.trusted));
                    }
                }
                String unparsed = librariesUnparsed.get(record.name);
                if (unparsed != null) {
                    libraries.remove(unparsed);
                }
            }
            return additions;
        }
        // Now we will see which libraries we want to load for this job.
        Map<String,LibraryRecord> librariesAdded = new LinkedHashMap<>();
        Map<String,LibraryRetriever> retrievers = new HashMap<>();
        TaskListener listener = execution.getOwner().getListener();
        for (LibraryResolver kind : ExtensionList.lookup(LibraryResolver.class)) {
            boolean kindTrusted = kind.isTrusted();
            for (LibraryConfiguration cfg : kind.forJob(build.getParent(), libraryVersions)) {
                String name = cfg.getName();
                if (!cfg.isImplicit() && !libraryVersions.containsKey(name)) {
                    continue; // not using this one at all
                }
                if (librariesAdded.containsKey(name)) {
                    listener.getLogger().println("Only using first definition of library " + name);
                    continue;
                }
                String version = cfg.defaultedVersion(libraryVersions.remove(name));
                Boolean changelog = cfg.defaultedChangelogs(libraryChangelogs.remove(name));
                String source = kind.getClass().getName();
                if (cfg instanceof LibraryResolver.ResolvedLibraryConfiguration) {
                    source = ((LibraryResolver.ResolvedLibraryConfiguration) cfg).getSource();
                }
                LibraryRetriever retriever = cfg.getRetriever();
                String libraryPath = null;
                if (retriever instanceof SCMBasedRetriever) {
                    libraryPath = ((SCMBasedRetriever) retriever).getLibraryPath();
                }
                librariesAdded.put(name, new LibraryRecord(name, version, kindTrusted, changelog, cfg.getCachingConfiguration(), source, libraryPath));
                retrievers.put(name, retriever);
            }
        }
        for (String name : librariesAdded.keySet()) {
            String unparsed = librariesUnparsed.get(name);
            if (unparsed != null) {
                libraries.remove(unparsed);
            }
        }
        // Record libraries we plan to load. We need LibrariesAction there first so variables can be interpolated.
        build.addAction(new LibrariesAction(new ArrayList<>(librariesAdded.values())));
        // Now actually try to retrieve the libraries.
        for (LibraryRecord record : librariesAdded.values()) {
            listener.getLogger().println("Loading library " + record.getLogString());
            for (URL u : retrieve(record, retrievers.get(record.name), listener, build, execution)) {
                additions.add(new Addition(u, record.trusted));
            }
        }
        return additions;
    }

    static @NonNull String[] parse(@NonNull String identifier) {
       int at = identifier.indexOf('@');
        if (at == -1) {
            return new String[] {identifier, null}; // pick up defaultVersion
        } else {
            return new String[] {identifier.substring(0, at), identifier.substring(at + 1)};
        }
    }

    private enum CacheStatus {
        VALID,
        EMPTY,
        DOES_NOT_EXIST,
        EXPIRED;
    }

    private static CacheStatus getCacheStatus(@NonNull LibraryCachingConfiguration cachingConfiguration, @NonNull final FilePath versionCacheDir)
          throws IOException, InterruptedException
    {
        if (cachingConfiguration.isRefreshEnabled()) {
            final long cachingMilliseconds = cachingConfiguration.getRefreshTimeMilliseconds();

            if(versionCacheDir.exists()) {
                if ((versionCacheDir.lastModified() + cachingMilliseconds) > System.currentTimeMillis()) {
                    if (getUrlsForLibDir(versionCacheDir).isEmpty()) {
                        return CacheStatus.EMPTY;
                    }
                    return CacheStatus.VALID;
                } else {
                    return CacheStatus.EXPIRED;
                }
            } else {
                return CacheStatus.DOES_NOT_EXIST;
            }
        } else {
            if (versionCacheDir.exists()) {
                if (getUrlsForLibDir(versionCacheDir).isEmpty()) {
                    return CacheStatus.EMPTY;
                }
                return CacheStatus.VALID;
            } else {
                return CacheStatus.DOES_NOT_EXIST;
            }
        }
    }
    
    /** Retrieve library files. */
    static List<URL> retrieve(@NonNull LibraryRecord record, @NonNull LibraryRetriever retriever, @NonNull TaskListener listener, @NonNull Run<?,?> run, @NonNull CpsFlowExecution execution) throws Exception {
        String name = record.name;
        String version = record.version;
        boolean changelog = record.changelog;
        String libraryLogString = record.getLogString();
        LibraryCachingConfiguration cachingConfiguration = record.cachingConfiguration;
        FilePath libDir = new FilePath(execution.getOwner().getRootDir()).child("libs/" + record.getDirectoryName());
        Boolean shouldCache = cachingConfiguration != null;
        final FilePath versionCacheDir = new FilePath(LibraryCachingConfiguration.getGlobalLibrariesCacheDir(), record.getDirectoryName());
        ReentrantReadWriteLock retrieveLock = getReadWriteLockFor(record.getDirectoryName());
        final FilePath lastReadFile = new FilePath(versionCacheDir, LibraryCachingConfiguration.LAST_READ_FILE);

        if(shouldCache && cachingConfiguration.isExcluded(version)) {
            listener.getLogger().println("Library " + libraryLogString + " is excluded from caching.");
            shouldCache = false;
        }

        //If the included versions is blank/null, cache irrespective
        //else check if that version is included and then cache only that version

        if((shouldCache && cachingConfiguration.isIncluded(version)) || (shouldCache && StringUtils.isBlank(cachingConfiguration.getIncludedVersionsStr()))) {
            retrieveLock.readLock().lockInterruptibly();
            try {
                CacheStatus cacheStatus = getCacheStatus(cachingConfiguration, versionCacheDir);
                if (cacheStatus == CacheStatus.DOES_NOT_EXIST || cacheStatus == CacheStatus.EXPIRED || cacheStatus == CacheStatus.EMPTY) {
                    retrieveLock.readLock().unlock();
                    retrieveLock.writeLock().lockInterruptibly();
                    try {
                      boolean retrieve = false;
                      switch (getCacheStatus(cachingConfiguration, versionCacheDir)) {
                          case VALID:
                              listener.getLogger().println("Library " + libraryLogString + " is cached. Copying from cache.");
                              break;
                          case EMPTY:
                              listener.getLogger().println("Library " + libraryLogString + " should have been cached but is empty, re-caching.");
                              deleteCacheDirIfExists(versionCacheDir);
                              retrieve = true;
                              break;
                          case DOES_NOT_EXIST:
                              retrieve = true;
                              break;
                          case EXPIRED:
                              long cachingMinutes = cachingConfiguration.getRefreshTimeMinutes();
                              listener.getLogger().println("Library " + libraryLogString + " is due for a refresh after " + cachingMinutes + " minutes, clearing.");
                              deleteCacheDirIfExists(versionCacheDir);
                              retrieve = true;
                              break;
                      }

                        if (retrieve) {
                            listener.getLogger().println("Caching library " + libraryLogString);
                            versionCacheDir.mkdirs();
                            // try to retrieve the library and delete the versionCacheDir if it fails
                            try {
                                retriever.retrieve(name, version, changelog, versionCacheDir, run, listener);
                                if (getUrlsForLibDir(versionCacheDir).isEmpty()) {
                                    // Get job name and build number from run
                                    String jobName = run.getParent().getFullName();
                                    String message = "Library " + libraryLogString + " is empty after retrieval in job " + jobName + ". Cleaning up cache directory.";
                                    listener.getLogger().println(message);
                                    // Log a warning at controller level as well
                                    LOGGER.log(Level.WARNING, message);
                                    deleteCacheDirIfExists(versionCacheDir);
                                    throw new AbortException("Library " + libraryLogString + " is empty.");
                                }
                                listener.getLogger().println("Library " + libraryLogString + " successfully cached.");
                            } catch (Exception e) {
                                listener.getLogger().println("Failed to cache library " + libraryLogString + ". Error message: " + e.getMessage() + ". Cleaning up cache directory.");
                                deleteCacheDirIfExists(versionCacheDir);
                                throw e;
                            }
                        }
                        retrieveLock.readLock().lock();
                    } finally {
                        retrieveLock.writeLock().unlock();
                    }
                } else {
                    listener.getLogger().println("Library " + libraryLogString + " is cached. Copying from cache.");
                }

                lastReadFile.touch(System.currentTimeMillis());
                versionCacheDir.withSuffix("-name.txt").write(name, "UTF-8");
                versionCacheDir.copyRecursiveTo(libDir);
            } finally {
              retrieveLock.readLock().unlock();
            }
        } else {
            retriever.retrieve(name, version, changelog, libDir, run, listener);
        }
        // Write the user-provided name to a file as a debugging aid.
        libDir.withSuffix("-name.txt").write(name, "UTF-8");

        // Replace any classes requested for replay:
        if (!record.trusted) {
            for (String clazz : ReplayAction.replacementsIn(execution)) {
                for (String root : new String[] {"src", "vars"}) {
                    String rel = root + "/" + clazz.replace('.', '/') + ".groovy";
                    FilePath f = libDir.child(rel);
                    if (f.exists()) {
                        String replacement = ReplayAction.replace(execution, clazz);
                        if (replacement != null) {
                            listener.getLogger().println("Replacing contents of " + rel);
                            f.write(replacement, null); // TODO as below, unsure of encoding used by Groovy compiler
                        }
                    }
                }
            }
        }
        List<URL> urls = getUrlsForLibDir(libDir, record);
        if (urls.isEmpty()) {
            throw new AbortException("Library " + name + " expected to contain at least one of src or vars directories");
        }
        return urls;
    }

    private static void deleteCacheDirIfExists(FilePath versionCacheDir) throws IOException, InterruptedException {
        if (versionCacheDir.exists()) {
            versionCacheDir.deleteRecursive();
            versionCacheDir.withSuffix("-name.txt").delete();
        }
    }

    private static List<URL> getUrlsForLibDir(FilePath libDir) throws MalformedURLException, IOException, InterruptedException {
        return getUrlsForLibDir(libDir, null);
    }

    private static List<URL> getUrlsForLibDir(FilePath libDir, LibraryRecord record) throws MalformedURLException, IOException, InterruptedException {
        List<URL> urls = new ArrayList<>();
        FilePath srcDir = libDir.child("src");
        if (srcDir.isDirectory()) {
            urls.add(srcDir.toURI().toURL());
        }
        FilePath varsDir = libDir.child("vars");
        if (varsDir.isDirectory()) {
            urls.add(varsDir.toURI().toURL());
            if (record != null) {
                for (FilePath var : varsDir.list("*.groovy")) {
                    record.variables.add(var.getBaseName());
                }
            }
        }
        return urls;
    }

    /**
     * Loads resources for {@link ResourceStep}.
     * @param execution a build
     * @param name a resource name, à la {@link Class#getResource(String)} but with no leading {@code /} allowed
     * @return a map from {@link LibraryRecord#name} to file contents
     */
    static @NonNull Map<String,String> findResources(@NonNull CpsFlowExecution execution, @NonNull String name, @CheckForNull String encoding) throws IOException, InterruptedException {
        Map<String,String> resources = new TreeMap<>();
        Queue.Executable executable = execution.getOwner().getExecutable();
        if (executable instanceof Run) {
            Run<?,?> run = (Run) executable;
            LibrariesAction action = run.getAction(LibrariesAction.class);
            if (action != null) {
                FilePath libs = new FilePath(run.getRootDir()).child("libs");
                for (LibraryRecord library : action.getLibraries()) {
                    FilePath libResources = libs.child(library.getDirectoryName() + "/resources/");
                    FilePath f = libResources.child(name);
                    if (!new File(f.getRemote()).getCanonicalFile().toPath().startsWith(new File(libResources.getRemote()).getCanonicalPath())) {
                        throw new AbortException(name + " references a file that is not contained within the library: " + library.name);
                    } else if (f.exists()) {
                        resources.put(library.name, readResource(f, encoding));
                    }
                }
            }
        }
        return resources;
    }

    private static String readResource(FilePath file, @CheckForNull String encoding) throws IOException, InterruptedException {
        try (InputStream in = file.read()) {
            if ("Base64".equals(encoding)) {
                return Base64.getEncoder().encodeToString(IOUtils.toByteArray(in));
            } else {
                return IOUtils.toString(in, encoding); // The platform default is used if encoding is null.
            }
        }
    }

    @Extension public static class GlobalVars extends GlobalVariableSet {

        @Override public Collection<GlobalVariable> forRun(Run<?,?> run) {
            if (run == null) {
                return Collections.emptySet();
            }
            LibrariesAction action = run.getAction(LibrariesAction.class);
            if (action == null) {
                return Collections.emptySet();
            }
            List<GlobalVariable> vars = new ArrayList<>();
            for (LibraryRecord library : action.getLibraries()) {
                for (String variable : library.variables) {
                    vars.add(new UserDefinedGlobalVariable(variable, new File(run.getRootDir(), "libs/" + library.getDirectoryName() + "/vars/" + variable + ".txt")));
                }
            }
            return vars;
        }

        // TODO implement forJob by checking each LibraryConfiguration and scanning SCMFileSystem when implemented (JENKINS-33273)

    }

    @Extension public static class LoadedLibraries extends OriginalLoadedScripts {

        @Override public Map<String,String> loadScripts(CpsFlowExecution execution) {
            Map<String,String> scripts = new HashMap<>();
            try {
                Queue.Executable executable = execution.getOwner().getExecutable();
                if (executable instanceof Run) {
                    Run<?,?> run = (Run) executable;
                    LibrariesAction action = run.getAction(LibrariesAction.class);
                    if (action != null) {
                        FilePath libs = new FilePath(run.getRootDir()).child("libs");
                        for (LibraryRecord library : action.getLibraries()) {
                            if (library.trusted) {
                                continue; // TODO JENKINS-41157 allow replay of trusted libraries if you have ADMINISTER
                            }
                            for (String rootName : new String[] {"src", "vars"}) {
                                FilePath root = libs.child(library.getDirectoryName() + "/" + rootName);
                                if (!root.isDirectory()) {
                                    continue;
                                }
                                for (FilePath groovy : root.list("**/*.groovy")) {
                                    String clazz = className(groovy.getRemote(), root.getRemote());
                                    scripts.put(clazz, groovy.readToString()); // TODO no idea what encoding the Groovy compiler uses
                                }
                            }
                        }
                    }
                }
            } catch (IOException | InterruptedException x) {
                LOGGER.log(Level.WARNING, null, x);
            }
            return scripts;
        }

        static String className(String groovy, String root) {
            return groovy.replaceFirst("^" + Pattern.quote(root) + "[/\\\\](.+)[.]groovy", "$1").replace('/', '.').replace('\\', '.');
        }

    }

    @Extension public static class Copier extends FlowCopier.ByRun {

        @Override public void copy(Run<?,?> original, Run<?,?> copy, TaskListener listener) throws IOException, InterruptedException {
            LibrariesAction action = original.getAction(LibrariesAction.class);
            if (action != null) {
                copy.addAction(new LibrariesAction(action.getLibraries()));
                FilePath libs = new FilePath(original.getRootDir()).child("libs");
                if (libs.isDirectory()) {
                    libs.copyRecursiveTo(new FilePath(copy.getRootDir()).child("libs"));
                }
            }
        }

    }

}