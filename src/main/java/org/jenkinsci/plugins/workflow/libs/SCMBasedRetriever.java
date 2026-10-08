/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.listeners.ItemListener;
import hudson.scm.SCM;
import hudson.slaves.WorkspaceList;
import hudson.util.FormValidation;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.steps.scm.GenericSCMStep;
import org.jenkinsci.plugins.workflow.steps.scm.SCMStep;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Functionality common to {@link SCMSourceRetriever} and {@link SCMRetriever}.
 */
public abstract class SCMBasedRetriever extends LibraryRetriever {

    private static final Logger LOGGER = Logger.getLogger(SCMBasedRetriever.class.getName());

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Non-final for write access via the Script Console")
    public static boolean INCLUDE_SRC_TEST_IN_LIBRARIES = Boolean.getBoolean(SCMSourceRetriever.class.getName() + ".INCLUDE_SRC_TEST_IN_LIBRARIES");

    /**
     * Matches ".." in positions where it would be treated as the parent directory.
     *
     * <p>Used to prevent {@link #libraryPath} from being used for directory traversal.
     */
    static final Pattern PROHIBITED_DOUBLE_DOT = Pattern.compile("(^|.*[\\\\/])\\.\\.($|[\\\\/].*)", Pattern.DOTALL);

    /**
     * The directories within a library that are special
     */
    private static final Collection<String> LIBRARY_DIRECTORIES = List.of("src", "vars", "resources");

    private boolean clone;

    /**
     * The path to the library inside of the SCM.
     *
     * {@code null} is the default and means that the library is in the root of the repository. Otherwise, the value is
     * considered to be a relative path inside of the repository and always ends in a forward slash
     *
     * @see #setLibraryPath
     */
    private @CheckForNull String libraryPath;

    public boolean isClone() {
        return clone;
    }

    @DataBoundSetter public void setClone(boolean clone) {
        this.clone = clone;
    }

    public String getLibraryPath() {
        return libraryPath;
    }

    @DataBoundSetter public void setLibraryPath(String libraryPath) {
        libraryPath = Util.fixEmptyAndTrim(libraryPath);
        if (libraryPath != null && !libraryPath.endsWith("/")) {
            libraryPath += '/';
        }
        this.libraryPath = libraryPath;
    }

    protected final void doRetrieve(String name, boolean changelog, @NonNull SCM scm, FilePath target, Run<?, ?> run, TaskListener listener) throws Exception {
        if (libraryPath != null) {
            if (PROHIBITED_DOUBLE_DOT.matcher(libraryPath).matches()) {
                throw new AbortException("Library path may not contain '..'");
            }
            if (!isRelativePath(libraryPath)) {
                throw new AbortException("Library path must be a relative path");
            }
        }
        if (clone && changelog) {
            listener.getLogger().println("WARNING: ignoring request to compute changelog in clone mode");
            changelog = false;
        }
        // Adapted from CpsScmFlowDefinition:
        SCMStep delegate = new GenericSCMStep(scm);
        delegate.setPoll(false); // TODO we have no API for determining if a given SCMHead is branch-like or tag-like; would we want to turn on polling if the former?
        delegate.setChangelog(changelog);
        Node node = Jenkins.get();
        if (clone) {
            if (libraryPath == null) {
                retrySCMOperation(listener, () -> {
                    delegate.checkout(run, target, listener, Jenkins.get().createLauncher(listener));
                    WorkspaceList.tempDir(target).deleteRecursive();
                    return null;
                });
                // ensure the clone has no symlinks
                rejectSpecialFiles(target, null);
            } else {
                FilePath root = target.child("root");
                retrySCMOperation(listener, () -> {
                    delegate.checkout(run, root, listener, Jenkins.get().createLauncher(listener));
                    WorkspaceList.tempDir(root).deleteRecursive();
                    return null;
                });
                // ensure the clone has no symlinks
                rejectSpecialFiles(root, libraryPath);
                FilePath subdir = root.child(libraryPath);
                if (!subdir.isDirectory()) {
                    throw new AbortException("Did not find " + libraryPath + " in checkout");
                }
                for (String content : LIBRARY_DIRECTORIES) {
                    FilePath contentDir = subdir.child(content);
                    if (contentDir.isDirectory()) {
                        LOGGER.fine(() -> "Moving " + content + " to top level in " + target);
                        contentDir.renameTo(target.child(content));
                    }
                }
                // root itself will be deleted below
            }
            if (!INCLUDE_SRC_TEST_IN_LIBRARIES) {
                FilePath srcTest = target.child("src/test");
                if (srcTest.isDirectory()) {
                    listener.getLogger().println("Excluding src/test/ from checkout of " + scm.getKey() + " so that library test code cannot be accessed by Pipelines.");
                    listener.getLogger().println("To remove this log message, move the test code outside of src/. To restore the previous behavior that allowed access to files in src/test/, pass -D" + SCMSourceRetriever.class.getName() + ".INCLUDE_SRC_TEST_IN_LIBRARIES=true to the java command used to start Jenkins.");
                    srcTest.deleteRecursive();
                }
            }
            for (FilePath child : target.list()) {
                String subdir = child.getName();
                switch (subdir) {
                case "src":
                    // TODO delete everything that is not *.groovy
                    break;
                case "vars":
                    // TODO delete everything that is not *.groovy or *.txt, incl. subdirs
                    break;
                case "resources":
                    // OK, leave it all
                    break;
                default:
                    child.deleteRecursive();
                    LOGGER.fine(() -> "Deleted " + child);
                }
            }
        } else { // !clone
            FilePath dir;
            var root = SystemProperties.getString(SCMSourceRetriever.class.getName() + ".root");
            if (root != null) {
                dir = node.createPath(root).child(target.getName());
            } else if (run.getParent() instanceof TopLevelItem) {
                FilePath baseWorkspace = node.getWorkspaceFor((TopLevelItem) run.getParent());
                if (baseWorkspace == null) {
                    throw new IOException(node.getDisplayName() + " may be offline");
                }
                String checkoutDirName = LibraryRecord.directoryNameFor(scm.getKey());
                dir = baseWorkspace.withSuffix(getFilePathSuffix() + "libs").child(checkoutDirName);
            } else { // should not happen, but just in case:
                throw new AbortException("Cannot check out in non-top-level build");
            }
            Computer computer = node.toComputer();
            if (computer == null) {
                throw new IOException(node.getDisplayName() + " may be offline");
            }
            try (WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(dir)) {
                // Write the SCM key to a file as a debugging aid.
                lease.path.withSuffix("-scm-key.txt").write(scm.getKey(), "UTF-8");
                retrySCMOperation(listener, () -> {
                    delegate.checkout(run, lease.path, listener, node.createLauncher(listener));
                    return null;
                });
                // Fail fast: reject symlinks early rather than after copying to libDir
                rejectSpecialFiles(lease.path, libraryPath);
                if (libraryPath == null) {
                    libraryPath = ".";
                }
                String excludes = INCLUDE_SRC_TEST_IN_LIBRARIES ? null : "src/test/";
                if (lease.path.child(libraryPath).child("src/test").exists()) {
                    listener.getLogger().println("Excluding src/test/ from checkout of " + scm.getKey() + " so that library test code cannot be accessed by Pipelines.");
                    listener.getLogger().println("To remove this log message, move the test code outside of src/. To restore the previous behavior that allowed access to files in src/test/, pass -D" + SCMSourceRetriever.class.getName() + ".INCLUDE_SRC_TEST_IN_LIBRARIES=true to the java command used to start Jenkins.");
                }
                // Cannot add WorkspaceActionImpl to private CpsFlowExecution.flowStartNodeActions; do we care?
                // Copy sources with relevant files from the checkout:
                lease.path.child(libraryPath).copyRecursiveTo("src/**/*.groovy,vars/*.groovy,vars/*.txt,resources/", excludes, target);
            }
        }
    }

    @Restricted(NoExternalUse.class)
    static boolean isRelativePath(@NonNull String libraryPath) {
        try {
            if (Path.of(libraryPath).isAbsolute()) {
                return false;
            }
        } catch (InvalidPathException e) {
            // as path is not valid for security reasons we would want to block it.
            return false;
        }
        // on windows we have a few more checks to do
        if (Functions.isWindows()
                && (libraryPath.startsWith("\\")
                        || // this is the root of the current drive something we also need to block.
                        // UNC paths and the \\?\ (long path prefix) are already rejected by isAbsolute
                        libraryPath.startsWith("/")
                        || // windows can equally use \ or / in paths
                        libraryPath.indexOf(":") != -1 // drive relative paths like `D:foo`
                )) {
            return false;
        }
        return true;
    }

    /**
     * Checks that no component of {@ code libraryPath} is a symbolic link, and that no file anywhere under {@code libraryPath/{src,vars,resources}} is not a regular file or directory.
     * @param checkoutRoot the root of the library clone/checkout
     * @param libraryPath a <em>relative<em> path found under checkoutRoot that contains the library resources
     * @throws IOException incase there was an I/O Error performing path checks
     * @throws AbortException if any offending file was found
     */
    static void rejectSpecialFiles(final FilePath checkoutRoot, final @CheckForNull String libraryPath) throws IOException {
        // we need to allow symlinks in checkoutRoot as they may be part of SCM metadata (e.g. mercurial)
        // or elsewhere outside of the library.
        // the libraryPath itself must never be a symlink, nor can the src, vars or resources folders be,
        // nor are they contain any symlinks

        // not using FilePath.containsSymlink to walk back as this is a boolean and we require more diagnostics if this fails.
        Path root = new File(checkoutRoot.getRemote()).toPath();

        // check there are no links in the library path segement
        // this is a relative path so will not walk all the way to root directory!
        Path toCheck = libraryPath == null ? null : Path.of(libraryPath);
        while (toCheck != null) {
            // resolve the file so we can check it
            Path p  = root.resolve(toCheck);
            if (Files.isSymbolicLink(p)) {
                throw new AbortException("Rejecting library: symlink found: " + root.relativize(p));
            }
            if (!Files.isDirectory(p)) {
                throw new AbortException("Rejecting library: expected directory: " + root.relativize(p));
            }
            toCheck = toCheck.getParent();
        }

        // now check the files in the library
        for (String s : LIBRARY_DIRECTORIES) {
            Path child = libraryPath == null ? Path.of(s) : Path.of(libraryPath).resolve(s);
            // check all files below this directory
            rejectSpecialFiles(root.resolve(child), root);
        }
    }

    // symlinks in a library allow reading arbitrary controller files via the global variable reference
    static void rejectSpecialFiles(FilePath target) throws IOException {
        Path p = new File(target.getRemote()).toPath();
        rejectSpecialFiles(p,p);
    }

    /**
     * Ensure that {@code toCheck} does not contain any special files, and is itself a directory.
     * @param toCheck the Path to check for special files
     * @param checkoutRoot base path to report errors against (so the actual path reported will be against the repository)
     * @throws IOException for any I/O error
     * @throws AbortException if we encounter any special files
     */
    static void rejectSpecialFiles(Path toCheck, Path checkoutRoot) throws IOException {
        if (Files.isSymbolicLink(toCheck)) {
            throw new AbortException("Rejecting library: symlink found: " + checkoutRoot.relativize(toCheck));
        }
        if (!Files.exists(toCheck)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(toCheck)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(p)) {
                    throw new AbortException("Rejecting library: symlink found: " + checkoutRoot.relativize(p));
                }
                if (!Files.isRegularFile(p) && !Files.isDirectory(p)) {
                    throw new AbortException("Rejecting library: non-regular file found: " + checkoutRoot.relativize(p));
                }
            }
        }
    }

    protected static <T> T retrySCMOperation(TaskListener listener, Callable<T> task) throws Exception{
        T ret = null;
        for (int retryCount = Jenkins.get().getScmCheckoutRetryCount(); retryCount >= 0; retryCount--) {
            try {
                ret = task.call();
                break;
            }
            catch (AbortException e) {
                // abort exception might have a null message.
                // If so, just skip echoing it.
                if (e.getMessage() != null) {
                    listener.error(e.getMessage());
                }
            }
            catch (InterruptedIOException e) {
                throw e;
            }
            catch (Exception e) {
                // checkout error not yet reported
                Functions.printStackTrace(e, listener.error("Checkout failed"));
            }

            if (retryCount == 0)   // all attempts failed
                throw new AbortException("Maximum checkout retry attempts reached, aborting");

            listener.getLogger().println("Retrying after 10 seconds");
            Thread.sleep(10000);
        }
        return ret;
    }

    // TODO there is WorkspaceList.tempDir but no API to make other variants
    private static String getFilePathSuffix() {
        return System.getProperty(WorkspaceList.class.getName(), "@");
    }

    protected abstract static class SCMBasedRetrieverDescriptor extends LibraryRetrieverDescriptor {

        @POST
        public FormValidation doCheckLibraryPath(@QueryParameter String libraryPath) {
            libraryPath = Util.fixEmptyAndTrim(libraryPath);
            if (libraryPath == null) {
                return FormValidation.ok();
            } else if (PROHIBITED_DOUBLE_DOT.matcher(libraryPath).matches()) {
                return FormValidation.error(Messages.SCMSourceRetriever_library_path_no_double_dot());
            }
            if (!isRelativePath(libraryPath)) {
                return FormValidation.error(Messages.SCMSourceRetriever_library_path_must_be_relative());
            }
            return FormValidation.ok();
        }

    }

    @Restricted(DoNotUse.class)
    @Extension
    public static class WorkspaceListener extends ItemListener {

        @Override
        public void onDeleted(Item item) {
            deleteLibsDir(item, item.getFullName());
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            deleteLibsDir(item, oldFullName);
        }

        private static void deleteLibsDir(Item item, String itemFullName) {
            if (item instanceof Job
                    && item.getClass()
                            .getName()
                            .equals("org.jenkinsci.plugins.workflow.job.WorkflowJob")) {
                synchronized (item) {
                    String base =
                            expandVariablesForDirectory(
                                    Jenkins.get().getRawWorkspaceDir(),
                                    itemFullName,
                                    item.getRootDir().getPath());
                    FilePath dir =
                            new FilePath(new File(base)).withSuffix(getFilePathSuffix() + "libs");
                    try {
                        if (dir.isDirectory()) {
                            LOGGER.log(
                                    Level.INFO,
                                    () -> "Deleting obsolete library workspace " + dir);
                            dir.deleteRecursive();
                        }
                    } catch (IOException | InterruptedException e) {
                        LOGGER.log(
                                Level.WARNING,
                                e,
                                () -> "Could not delete obsolete library workspace " + dir);
                    }
                }
            }
        }

        private static String expandVariablesForDirectory(
                String base, String itemFullName, String itemRootDir) {
            // If the item is moved, it is too late to look up its original workspace location by
            // the time we get the notification. See:
            // https://github.com/jenkinsci/jenkins/blob/f03183ab09ce5fb8f9f4cc9ccee42a3c3e6b2d3e/core/src/main/java/jenkins/model/Jenkins.java#L2567-L2576
            Map<String, String> properties = new HashMap<>();
            properties.put("JENKINS_HOME", Jenkins.get().getRootDir().getPath());
            properties.put("ITEM_ROOTDIR", itemRootDir);
            properties.put("ITEM_FULLNAME", itemFullName); // legacy, deprecated
            properties.put(
                    "ITEM_FULL_NAME", itemFullName.replace(':', '$')); // safe, see JENKINS-12251
            return Util.replaceMacro(base, Collections.unmodifiableMap(properties));
        }
    }

}
