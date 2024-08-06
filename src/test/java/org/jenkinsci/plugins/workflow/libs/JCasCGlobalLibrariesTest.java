package org.jenkinsci.plugins.workflow.libs;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.jvnet.hudson.test.LoggerRule;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIn.in;
import static org.hamcrest.core.IsNot.not;

public class JCasCGlobalLibrariesTest {

    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();
    public LoggerRule logging = new LoggerRule();

    @Rule
    public RuleChain chain = RuleChain.outerRule(logging.record(Logger.getLogger("io.jenkins.plugins.casc.BaseConfigurator"), Level.INFO)
                    .capture(2048))
            .around(j);

    @Test
    @ConfiguredWithCode("JCasCGlobalLibrariesTest.yml")
    public void isBaseConfiguratorComplainingAboutOwner() {
        //WARNING i.j.p.casc.BaseConfigurator#createAttribute: Can't handle class jenkins.plugins.git.GitSCMSource#owner: type is abstract but not Describable.
        assertThat("Can't handle class jenkins.plugins.git.GitSCMSource#owner: type is abstract but not Describable.",
                not(in(logging.getMessages())
        ));
    }
}
