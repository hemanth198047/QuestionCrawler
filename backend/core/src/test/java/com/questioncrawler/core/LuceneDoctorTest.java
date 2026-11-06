package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.LuceneDoctor;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceConfigurationError;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The failure "Lucene50Codec: Unable to get public no-arg constructor" comes from an old Lucene jar (lucene-backward-codecs 7.x/8.x) on
 * the classpath next to Lucene 9. The doctor must recognise that situation and name the jar.
 */
class LuceneDoctorTest {

    @TempDir
    Path tmp;

    private Path jar(String name, String... entries) throws IOException {
        Path file = tmp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new JarEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private ClassLoader loaderWith(Path... jars) throws IOException {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        return new URLClassLoader(urls, LuceneDoctorTest.class.getClassLoader());
    }

    @Test
    void theClasspathOfThisProjectIsHealthy() {
        var report = LuceneDoctor.inspect(LuceneDoctorTest.class.getClassLoader());
        assertTrue(report.healthy(), report.problems().toString());
        assertNotEquals("unknown", report.luceneVersion());
        assertTrue(report.fixes().isEmpty());
        assertTrue(report.jars().stream().anyMatch(j -> !j.providers().isEmpty()), "the plug-ins Lucene registers were seen");
    }

    @Test
    void anOldBackwardCodecsJarNextToLucene9IsNamedWithTheExactFix() throws Exception {
        Path old = jar("lucene-backward-codecs-8.11.2.jar", "META-INF/services/org.apache.lucene.codecs.Codec", "org.apache.lucene.codecs.lucene50.Lucene50Codec\n");
        var report = LuceneDoctor.inspect(loaderWith(old));
        assertFalse(report.healthy());
        String problems = String.join(" ", report.problems());
        assertTrue(problems.contains("org.apache.lucene.codecs.lucene50.Lucene50Codec") && problems.contains("lucene-backward-codecs-8.11.2.jar") && problems.contains("cannot be created"), problems);
        String fixes = String.join(" ", report.fixes());
        assertTrue(fixes.contains("exclude group: 'org.apache.lucene', module: 'lucene-backward-codecs'"), fixes);
        assertTrue(fixes.contains("gradle :app:dependencyInsight --dependency lucene-backward-codecs --configuration runtimeClasspath"), fixes);
    }

    @Test
    void severalCopiesAndMixedVersionsOfLuceneAreReported() throws Exception {
        Path a = jar("lucene-core-8.11.2.jar", "org/apache/lucene/util/Version.class", "x");
        Path b = jar("lucene-analysis-common-9.11.1.jar", "META-INF/services/org.apache.lucene.codecs.PostingsFormat", "");
        var report = LuceneDoctor.inspect(loaderWith(a, b));
        String problems = String.join(" ", report.problems());
        assertTrue(problems.contains("copies of the Lucene core"), problems);
        assertTrue(problems.contains("different versions are mixed") && problems.contains("8.11.2") && problems.contains("9.11.1"), problems);
        assertTrue(String.join(" ", report.fixes()).contains("module: 'lucene-core'"), "the older core is the one to exclude: " + report.fixes());
    }

    @Test
    void theErrorMessageNamesTheProblemInsteadOfLucenesOwnWords() throws Exception {
        Path old = jar("lucene-backward-codecs-7.7.3.jar", "META-INF/services/org.apache.lucene.codecs.Codec", "org.apache.lucene.codecs.lucene50.Lucene50Codec\n");
        var report = LuceneDoctor.inspect(loaderWith(old));
        var lucene = new ServiceConfigurationError("org.apache.lucene.codecs.Codec: Provider org.apache.lucene.codecs.lucene50.Lucene50Codec could not be instantiated",
                new NoSuchMethodException("Lucene50Codec.<init>()"));
        assertTrue(LuceneDoctor.isLuceneProblem(lucene));
        assertFalse(LuceneDoctor.isLuceneProblem(new IllegalStateException("something else entirely")));
        String message = LuceneDoctor.explain(lucene, report);
        assertTrue(message.startsWith("The search library (Lucene) cannot start."), message);
        assertTrue(message.contains("lucene-backward-codecs-7.7.3.jar") && message.contains("exclude group"), message);
        assertEquals(-1, message.indexOf("No conflicting Lucene jar was detected"));
        // when nothing conflicting is found the message says what else to try
        String healthy = LuceneDoctor.explain(lucene, LuceneDoctor.inspect(LuceneDoctorTest.class.getClassLoader()));
        assertTrue(healthy.contains("No conflicting Lucene jar was detected") && healthy.contains("index"), healthy);
    }
}
