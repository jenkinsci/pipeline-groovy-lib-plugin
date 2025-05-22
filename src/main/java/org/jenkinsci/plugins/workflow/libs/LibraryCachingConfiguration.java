package org.jenkinsci.plugins.workflow.libs;

import hudson.Extension;
import hudson.FilePath;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

public final class LibraryCachingConfiguration extends AbstractDescribableImpl<LibraryCachingConfiguration> {
    
    private static final Logger LOGGER = Logger.getLogger(LibraryCachingConfiguration.class.getName());
    
    private int refreshTimeMinutes;
    private String excludedVersionsStr;
    private String includedVersionsStr;

    private static final String VERSIONS_SEPARATOR = " ";
    private static final String GLOBAL_LIBRARIES_DIR = "global-libraries-cache";
    public static final String LAST_READ_FILE = "last_read";

    @DataBoundConstructor public LibraryCachingConfiguration(int refreshTimeMinutes, String excludedVersionsStr) {
        this.refreshTimeMinutes = refreshTimeMinutes;
        this.excludedVersionsStr = excludedVersionsStr;
        this.includedVersionsStr = "";
    }

    /*
     * Visible for testing ...
     */
    @Restricted(NoExternalUse.class)
    LibraryCachingConfiguration(int refreshTimeMinutes, String excludedVersionsStr, String includedVersionsStr) {
        this.refreshTimeMinutes = refreshTimeMinutes;
        this.excludedVersionsStr = excludedVersionsStr;
        this.includedVersionsStr = includedVersionsStr;
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
    public String getIncludedVersionsStr() {
        if(StringUtils.isBlank(includedVersionsStr)){
            return null;
        }
            return includedVersionsStr;
    }

    @DataBoundSetter
    public void setIncludedVersionsStr(String includedVersionsStr) {
        this.includedVersionsStr = includedVersionsStr;
    }

    private List<String> getExcludedVersions() {
        if (excludedVersionsStr == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(excludedVersionsStr.split(VERSIONS_SEPARATOR));
    }

    private List<String> getIncludedVersions() {
        if (includedVersionsStr == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(includedVersionsStr.split(VERSIONS_SEPARATOR));
    }

    public Boolean isExcluded(String version) {
        // exit early if the version passed in is null or empty
        if (StringUtils.isBlank(version)) {
            return false;
        }
        for (String it : getExcludedVersions()) {
            // confirm that the excluded versions aren't null or empty
            // and if the version contains the exclusion thus it can be
            // anywhere in the string.
            if (StringUtils.isNotBlank(it) && version.contains(it)){
                return true;
            }
        }
        return false;
    }

    public Boolean isIncluded(String version) {
        // exit early if the version passed in is null or empty
        if (StringUtils.isBlank(version)) {
            return false;
        }
        for (String it : getIncludedVersions()) {
            // works on empty or null included versions
            // and if the version contains the inclusion thus it can be
            // anywhere in the string.
            if (StringUtils.isNotBlank(it) && version.contains(it)){
                return true;
            }
        }
        return false;
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