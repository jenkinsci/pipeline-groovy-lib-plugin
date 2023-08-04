package org.jenkinsci.plugins.workflow.libs;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
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

    private static final String VERSIONS_SEPARATOR = " ";
    public static final String GLOBAL_LIBRARIES_DIR = "global-libraries-cache";
    public static final String LAST_READ_FILE = "last_read";

    @DataBoundConstructor public LibraryCachingConfiguration(int refreshTimeMinutes, String excludedVersionsStr) {
        this.refreshTimeMinutes = refreshTimeMinutes;
        this.excludedVersionsStr = excludedVersionsStr;
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

    private List<String> getExcludedVersions() {
        if (excludedVersionsStr == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(excludedVersionsStr.split(VERSIONS_SEPARATOR));
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

    @Override public String toString() {
        return "LibraryCachingConfiguration{refreshTimeMinutes=" + refreshTimeMinutes + ", excludedVersions="
                + excludedVersionsStr + '}';
    }

    public static FilePath getGlobalLibrariesCacheDir() {
        Jenkins jenkins = Jenkins.get();
        return new FilePath(jenkins.getRootPath(), LibraryCachingConfiguration.GLOBAL_LIBRARIES_DIR);
    }

    @Extension public static class DescriptorImpl extends Descriptor<LibraryCachingConfiguration> {
        public FormValidation doClearCache(@QueryParameter String name, @QueryParameter String cachedLibraryRef, @QueryParameter boolean forceDelete) throws InterruptedException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            String cacheDirName = null;
            try {
                if (LibraryCachingConfiguration.getGlobalLibrariesCacheDir().exists()) {
                   outer: for (FilePath libraryCache : LibraryCachingConfiguration.getGlobalLibrariesCacheDir().listDirectories()) {
                        for (FilePath libraryNamePath : libraryCache.list("*-name.txt")) {
                            if (libraryNamePath.readToString().startsWith(name + "@")) {
                                FilePath libraryCachePath = libraryNamePath.getParent();
                                if (libraryCachePath != null) {
                                    FilePath versionCachePath = new FilePath(libraryCachePath, libraryNamePath.getName().replace("-name.txt", ""));
                                    LOGGER.log(Level.FINER, "Safe deleting cache for {0}", name);
                                    ReentrantReadWriteLock retrieveLock = LibraryAdder.getReadWriteLockFor(libraryCachePath.getName());
                                    if (forceDelete || retrieveLock.writeLock().tryLock(10, TimeUnit.SECONDS)) {
                                        if (forceDelete) {
                                            LOGGER.log(Level.FINER, "Force deleting cache for {0}", name);
                                        } else {
                                            LOGGER.log(Level.FINER, "Safe deleting cache for {0}", name);
                                        }
                                        try {
                                            if (StringUtils.isNotEmpty(cachedLibraryRef)) {
                                                if (libraryNamePath.readToString().equals(name + "@" + cachedLibraryRef)) {
                                                    cacheDirName = name + "@" + cachedLibraryRef;
                                                    libraryNamePath.delete();
                                                    versionCachePath.deleteRecursive();
                                                    break outer;
                                                }
                                            } else {
                                                cacheDirName = name;
                                                libraryCachePath.deleteRecursive();
                                                break outer;
                                            }
                                        } finally {
                                            if (!forceDelete) {
                                                retrieveLock.writeLock().unlock();
                                            }
                                        }
                                    } else {
                                        return FormValidation.error("The cache dir could not be deleted because it is currently being used by another thread. Please try again.");
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException ex) {
                return FormValidation.error(ex, String.format("The cache dir %s was not deleted successfully", cacheDirName));
            }
            
            if (cacheDirName == null) {
                return FormValidation.ok(String.format("The version %s was not found for library %s.", cachedLibraryRef, name));
            } else {
                return FormValidation.ok(String.format("The cache dir %s was deleted successfully.", cacheDirName));
            }
        }

    }
}
