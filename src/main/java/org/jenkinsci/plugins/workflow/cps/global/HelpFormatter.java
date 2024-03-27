package org.jenkinsci.plugins.workflow.cps.global;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.markup.MarkupFormatter;
import hudson.markup.RawHtmlMarkupFormatter;
import io.jenkins.plugins.MarkdownFormatter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Formatter for the help of a library step.
 * Allows to use markdown and html for the help. Avoids using the globally configured formatter
 * that might not fit.
 */
@Restricted(NoExternalUse.class)
public abstract class HelpFormatter implements ExtensionPoint {

    public boolean isApplicable(String file) {
        return getFile(file).exists();
    }

    private File getFile(String file) {
        return new File(file + getExtension());
    }
    protected abstract String getExtension();

    protected abstract MarkupFormatter getFormatter();

    public final String translate(String file) throws IOException {
        return "<div class=\"library-step-help\">\n" +
                getFormatter().translate(readFile(file)) +
                "</div>";
    }

    private String readFile(String file) throws IOException {
        // Util.escape translates \n but not \r, and we do not know what platform the library will
        // be checked out on:
        return FileUtils.readFileToString(getFile(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static List<HelpFormatter> all() {
        return Jenkins.get().getExtensionList(HelpFormatter.class);
    }

    @Extension(ordinal = 99999)
    public static class MarkDown extends HelpFormatter {

        private static final String EXTENSION = ".md";

        @Override
        protected String getExtension() {
            return EXTENSION;
        }

        @Override
        protected MarkupFormatter getFormatter() {
            return new MarkdownFormatter();
        }
    }

    /**
     * formatter for html files based on the OWASP formatter
     */
    @Extension(ordinal = 50000)
    public static class HtmlFormatter extends HelpFormatter {

        private static final String EXTENSION = ".html";

        @Override
        protected String getExtension() {
            return EXTENSION;
        }

        protected MarkupFormatter getFormatter() {
            return new RawHtmlMarkupFormatter(true);
        }
    }

    /**
     * Formatter for txt files, uses the globally configured formatter
     */
    @Extension(ordinal = -99999)
    public static class Default extends HelpFormatter {

        private static final String EXTENSION = ".txt";

        @Override
        protected String getExtension() {
            return EXTENSION;
        }

        @Override
        protected MarkupFormatter getFormatter() {
            return Jenkins.get().getMarkupFormatter();
        }
    }
}
