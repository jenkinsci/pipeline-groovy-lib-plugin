package org.jenkinsci.plugins.workflow.libs;

import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;

final class LibraryTestUtils {
    private LibraryTestUtils(){}

    static LibraryConfiguration defineLibraryUsingGrab(String libraryName, GitSampleRepoRule sampleRepo) throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/pkg/Wrapper.groovy",
                """
                        package pkg
                        @Grab('commons-primitives:commons-primitives:1.0')
                        import org.apache.commons.collections.primitives.ArrayIntList
                        class Wrapper {static def list() {new ArrayIntList()}}""");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        return new LibraryConfiguration(libraryName, new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString())));
    }
}
