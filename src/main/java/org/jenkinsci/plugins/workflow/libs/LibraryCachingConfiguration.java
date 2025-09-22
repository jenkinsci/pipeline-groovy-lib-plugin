package org.jenkinsci.plugins.workflow.libs;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.File;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;

public final class LibraryCachingConfiguration extends AbstractDescribableImpl<LibraryCachingConfiguration> {
    
    private static final Logger LOGGER = Logger.getLogger(LibraryCachingConfiguration.class.getName());
    
    private final int refreshTimeMinutes;
    private final String excludedVersionsStr;
    private String includedVersionsStr;

    private static final String VERSIONS_SEPARATOR = " ";
    private static final String GLOBAL_LIBRARIES_DIR = "global-libraries-cache";
    public static final String LAST_READ_FILE = "last_read";

    @DataBoundConstructor public LibraryCachingConfiguration(int refreshTimeMinutes, String excludedVersionsStr) {
        this.refreshTimeMinutes = refreshTimeMinutes;
        this.excludedVersionsStr = excludedVersionsStr;
    }

    @VisibleForTesting // TODO better inline
    @Restricted(NoExternalUse.class)
    LibraryCachingConfiguration(int refreshTimeMinutes, String excludedVersionsStr, String includedVersionsStr) {
        this(refreshTimeMinutes, excludedVersionsStr);
        setIncludedVersionsStr(includedVersionsStr);
    }

    public int getRefreshTimeMinutes() {
        return refreshTimeMinutes;
    }

    public long getRefreshTimeMilliseconds() {
        return Long.valueOf(getRefreshTimeMinutes()) * 60000;
    }

    public Boolean isRefreshEnabled() {
        return refreshTimeMinutes > 0;
    }

    public String getExcludedVersionsStr() {
        return excludedVersionsStr;
    }

    @CheckForNull
    public String getIncludedVersionsStr() {
        return Util.fixEmptyAndTrim(includedVersionsStr);
    }

    @DataBoundSetter
    public void setIncludedVersionsStr(String includedVersionsStr) {
        this.includedVersionsStr = Util.fixEmptyAndTrim(includedVersionsStr);
    }

    private static Stream<String> split(@CheckForNull String list) {
        if (list == null) {
            return Stream.empty();
        }
        return Stream.of(list.split(VERSIONS_SEPARATOR)).filter(v -> !v.isBlank());
    }

    public Boolean isExcluded(String version) {
        // exit early if the version passed in is null or empty
        if (Util.fixEmpty(version) == null) {
            return false;
        }
        // confirm if the version contains the exclusion thus it can be
        // anywhere in the string.
        return split(excludedVersionsStr).anyMatch(version::contains);
    }

    public Boolean isIncluded(String version) {
        // exit early if the version passed in is null or empty
        if (Util.fixEmpty(version) == null) {
            return false;
        }
        return split(includedVersionsStr).anyMatch(version::contains);
    }

    @Override public String toString() {
        return "LibraryCachingConfiguration{refreshTimeMinutes=" + refreshTimeMinutes + ", excludedVersions="
                + excludedVersionsStr + '}';
    }

    public static FilePath getGlobalLibrariesCacheDir() {
        String cacheRootDirOverride = SystemProperties.getString(LibraryCachingConfiguration.class.getName() + ".cacheRootDir");
        File cacheRootDir = cacheRootDirOverride != null ? new File(cacheRootDirOverride) : new File(Jenkins.get().getRootDir(), GLOBAL_LIBRARIES_DIR);
        return new FilePath(cacheRootDir);
    }

    @Extension public static class DescriptorImpl extends Descriptor<LibraryCachingConfiguration> {
        public FormValidation doClearCache(@QueryParameter String name, @QueryParameter boolean forceDelete) throws InterruptedException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            try {
                if (LibraryCachingConfiguration.getGlobalLibrariesCacheDir().exists()) {
                    for (FilePath libraryNamePath : LibraryCachingConfiguration.getGlobalLibrariesCacheDir().list("*-name.txt")) {
                        // Libraries configured in distinct locations may have the same name. Since only admins are allowed here, this is not a huge issue, but it is probably unexpected.
                        String cacheName;
                        try (InputStream stream = libraryNamePath.read()) {
                            cacheName = IOUtils.toString(stream, StandardCharsets.UTF_8);
                        }
                        if (cacheName.equals(name)) {
                            FilePath libraryCachePath = LibraryCachingConfiguration.getGlobalLibrariesCacheDir()
                                    .child(libraryNamePath.getName().replace("-name.txt", ""));
                            if (forceDelete) {
                                LOGGER.log(Level.FINER, "Force deleting cache for {0}", name);
                                libraryCachePath.deleteRecursive();
                                libraryNamePath.delete();
                            } else {
                                LOGGER.log(Level.FINER, "Safe deleting cache for {0}", name);
                                ReentrantReadWriteLock retrieveLock = LibraryAdder.getReadWriteLockFor(libraryCachePath.getName());
                                if (retrieveLock.writeLock().tryLock(10, TimeUnit.SECONDS)) {
                                    try {
                                        libraryCachePath.deleteRecursive();
                                        libraryNamePath.delete();
                                    } finally {
                                        retrieveLock.writeLock().unlock();
                                    }
                                } else {
                                    return FormValidation.error("The cache dir could not be deleted because it is currently being used by another thread. Please try again.");
                                }
                            }
                        }
                    }
                }
            } catch (IOException ex) {
                return FormValidation.error(ex, "The cache dir was not deleted successfully");
            }
            return FormValidation.ok("The cache dir was deleted successfully.");
        }
    }
}