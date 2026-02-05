package org.jenkinsci.plugins.workflow.cps.global;

import groovy.lang.Binding;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.jenkinsci.plugins.workflow.cps.CpsCompilationErrorsException;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * Global variable backed by user-supplied script.
 *
 * @author Kohsuke Kawaguchi
 */
// not @Extension because these are instantiated programmatically
public class UserDefinedGlobalVariable extends GlobalVariable {
    private final String help;
    private final String name;

    public UserDefinedGlobalVariable(String name, String help) {
        this.name = name;
        this.help = help;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) throws Exception {
        Binding binding = script.getBinding();
        Object instance;
        if (binding.hasVariable(getName())) {
            instance = binding.getVariable(getName());
        } else {
            CpsThread c = CpsThread.current();
            if (c==null)
                throw new IllegalStateException("Expected to be called from CpsThread");

            try {
                instance = c.getExecution().getShell().getClassLoader().loadClass(getName()).newInstance();
            } catch(MultipleCompilationErrorsException ex) {
                // Convert to a serializable exception, see JENKINS-40109.
                throw new CpsCompilationErrorsException(ex);
            }
            /* We could also skip registration of vars in GroovyShellDecoratorImpl and use:
                 instance = c.getExecution().getShell().parse(source(".groovy"));
               But then the source will appear in CpsFlowExecution.loadedScripts and be offered up for ReplayAction.
               We might *want* to support replay of global vars & classes at some point, but to make it actually work
               we would also need to start calling LoadStepExecution.Replacer.
            */
            binding.setVariable(getName(), instance);
        }
        return instance;
    }

    /**
     * Loads help from user-defined file, if available.
     */
    public @CheckForNull String getHelpHtml() throws IOException {
        for (HelpFormatter formatter: HelpFormatter.all()) {
            if (formatter.isApplicable(help)) {
                return formatter.translate(help);
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UserDefinedGlobalVariable that = (UserDefinedGlobalVariable) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

}
