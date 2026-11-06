package com.questioncrawler.core.search;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ServiceConfigurationError;

/**
 * Finds out why Lucene (the search library) cannot start. The usual cause is a classpath that holds more than one Lucene: for example a
 * Lucene 9 core next to an old 7.x/8.x {@code lucene-backward-codecs} jar brought in by another library. Lucene then fails while it scans
 * its plug-ins ("Unable to get public no-arg constructor" for {@code Lucene50Codec}). This class looks at the running classpath, names the
 * offending jar and says how to remove it.
 */
public final class LuceneDoctor {

    /** One jar that contributes Lucene classes or plug-ins. */
    public record Jar(String path, String name, String module, String version, List<String> providers) {}

    public record Report(String luceneVersion, List<Jar> jars, List<String> problems, List<String> fixes) {
        public boolean healthy() {
            return problems.isEmpty();
        }
    }

    private static final String[] SERVICES = {"org.apache.lucene.codecs.Codec", "org.apache.lucene.codecs.PostingsFormat", "org.apache.lucene.codecs.DocValuesFormat"};
    private static final Pattern JAR_NAME = Pattern.compile("^(lucene-.+?)-(\\d+(?:\\.\\d+)*)(?:[-.][A-Za-z0-9.]+)?\\.jar$");

    private static volatile Report cached;

    private LuceneDoctor() {}

    /** Computed once per run of the program (the classpath does not change). */
    public static Report cachedInspection() {
        Report r = cached;
        if (r == null) {
            r = inspect(LuceneDoctor.class.getClassLoader());
            cached = r;
        }
        return r;
    }

    public static Report inspect(ClassLoader loader) {
        Map<String, Jar> jars = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> badModules = new LinkedHashSet<>();

        // every jar that holds a Lucene core (Version.class)
        List<String> cores = new ArrayList<>();
        for (URL url : resources(loader, "org/apache/lucene/util/Version.class")) {
            String path = jarPath(url);
            cores.add(path);
            jars.computeIfAbsent(path, p -> describe(p, new ArrayList<>()));
        }
        // every jar that registers plug-ins, and the plug-ins it registers
        List<String[]> providers = new ArrayList<>(); // {class, jarPath, service}
        for (String service : SERVICES) {
            for (URL url : resources(loader, "META-INF/services/" + service)) {
                String path = jarPath(url);
                List<String> declared = readProviders(url);
                Jar jar = jars.computeIfAbsent(path, p -> describe(p, new ArrayList<>()));
                jar.providers().addAll(declared);
                declared.forEach(c -> providers.add(new String[] {c, path, service}));
            }
        }
        // every lucene-*.jar on the class path (some may not register anything)
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (entry.replace('\\', '/').matches(".*/lucene-[^/]+\\.jar$") || entry.matches("lucene-[^/\\\\]+\\.jar")) {
                jars.computeIfAbsent(entry, p -> describe(p, new ArrayList<>()));
            }
        }

        String version = "unknown";
        try {
            version = Class.forName("org.apache.lucene.util.Version", true, loader).getField("LATEST").get(null).toString();
        } catch (Throwable e) {
            problems.add("The Lucene core cannot be loaded at all (" + shortReason(e) + ").");
        }

        // 1. several cores
        if (cores.size() > 1) {
            problems.add("There are " + cores.size() + " copies of the Lucene core on the classpath: " + String.join(", ", cores.stream().map(LuceneDoctor::fileName).toList()) + ".");
            cores.stream().skip(1).forEach(c -> badModules.add(jars.get(c).module() == null ? c : jars.get(c).module()));
        }
        // 2. jars of different Lucene versions
        Map<String, List<Jar>> byVersion = new LinkedHashMap<>();
        for (Jar j : jars.values()) {
            if (j.version() != null) {
                byVersion.computeIfAbsent(j.version(), v -> new ArrayList<>()).add(j);
            }
        }
        if (byVersion.size() > 1) {
            String newest = byVersion.keySet().stream().max(LuceneDoctor::compareVersions).orElse(null);
            StringBuilder sb = new StringBuilder("Lucene jars of different versions are mixed: ");
            byVersion.forEach((v, list) -> sb.append(v).append(" (").append(String.join(", ", list.stream().map(Jar::name).toList())).append("); "));
            problems.add(sb.toString().strip());
            byVersion.forEach((v, list) -> { if (!v.equals(newest)) list.forEach(j -> badModules.add(j.module() == null ? j.path() : j.module())); });
        }
        // 3. plug-ins that cannot be created
        for (String[] p : providers) {
            String reason = tryLoad(loader, p[0]);
            if (reason != null) {
                problems.add("The Lucene plug-in " + p[0] + " (registered by " + fileName(p[1]) + ") cannot be created: " + reason + ".");
                Jar j = jars.get(p[1]);
                badModules.add(j == null || j.module() == null ? p[1] : j.module());
            }
        }

        List<String> fixes = new ArrayList<>();
        if (!problems.isEmpty()) {
            for (String m : badModules) {
                if (m.startsWith("lucene-")) {
                    fixes.add("Exclude it: in backend/app/build.gradle add   configurations.configureEach { exclude group: 'org.apache.lucene', module: '" + m + "' }   "
                            + "(then make sure backend/core/build.gradle still lists lucene-core and lucene-analysis-common with the same version).");
                    fixes.add("To find which library brings it in: gradle :app:dependencyInsight --dependency " + m + " --configuration runtimeClasspath");
                } else {
                    fixes.add("This file contains its own copy of Lucene and must be removed from the classpath: " + m);
                }
            }
            fixes.add("Also check that no Lucene .jar sits in a lib/ folder, in an IDE 'libraries' list, or in a fat jar that your project includes.");
            fixes.add("To list every Lucene jar the app uses: gradle :app:dependencies --configuration runtimeClasspath   and look for 'lucene'.");
        }
        return new Report(version, List.copyOf(jars.values()), List.copyOf(problems), List.copyOf(fixes));
    }

    /** Is this failure one of Lucene's own start-up failures? */
    public static boolean isLuceneProblem(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            String m = String.valueOf(x.getMessage());
            if (x instanceof ServiceConfigurationError || (x instanceof LinkageError && m.contains("lucene")) || m.contains("org.apache.lucene.codecs")
                    || m.contains("SPI class of type org.apache.lucene")) {
                return true;
            }
            if (x.getCause() == x) {
                break;
            }
        }
        return false;
    }

    /** A message a person can act on, in place of Lucene's own. */
    public static String explain(Throwable t) {
        return explain(t, cachedInspection());
    }

    public static String explain(Throwable t, Report report) {
        StringBuilder sb = new StringBuilder("The search library (Lucene) cannot start. ");
        if (report.healthy()) {
            sb.append("No conflicting Lucene jar was detected on this classpath, so the cause may be a damaged index folder or an incompatible index from another Lucene version: ")
                    .append("try the 'Re-sort' action (or delete the 'index' folder inside your storage folder) and start again. Lucene said: ").append(shortReason(t)).append('.');
        } else {
            sb.append("The classpath holds a conflicting Lucene. ").append(String.join(" ", report.problems())).append(' ').append(String.join(" ", report.fixes()));
            sb.append(" (Lucene said: ").append(shortReason(t)).append(')');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static List<URL> resources(ClassLoader loader, String name) {
        try {
            return Collections.list(loader.getResources(name));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> readProviders(URL url) {
        List<String> out = new ArrayList<>();
        try (InputStream in = url.openStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String l = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
                if (!l.isBlank()) {
                    out.add(l.strip());
                }
            }
        } catch (IOException e) {
            // an unreadable file contributes nothing
        }
        return out;
    }

    private static String jarPath(URL url) {
        String s = url.toString();
        if (s.startsWith("jar:")) {
            s = s.substring(4);
            int bang = s.indexOf("!/");
            if (bang >= 0) {
                s = s.substring(0, bang);
            }
        }
        if (s.startsWith("file:")) {
            s = s.substring(5);
        }
        s = java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        for (String inside : new String[] {"/META-INF/services/", "/org/apache/lucene/"}) { // a classes folder rather than a jar: name the folder
            int at = s.indexOf(inside);
            if (at >= 0 && !s.toLowerCase().endsWith(".jar")) {
                s = s.substring(0, at);
            }
        }
        return s.matches("^/[A-Za-z]:/.*") ? s.substring(1) : s; // /C:/dir -> C:/dir
    }

    private static Jar describe(String path, List<String> providers) {
        String name = fileName(path);
        Matcher m = JAR_NAME.matcher(name);
        if (m.matches()) {
            return new Jar(path, name, m.group(1), m.group(2), providers);
        }
        return new Jar(path, name, null, null, providers);
    }

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i]) : 0;
            int yi = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    /** null when the plug-in can be created; otherwise why not. */
    private static String tryLoad(ClassLoader loader, String className) {
        try {
            Class<?> c = Class.forName(className, true, loader);
            c.getConstructor().newInstance();
            return null;
        } catch (Throwable e) {
            return shortReason(e);
        }
    }

    private static String shortReason(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage();
        String text = root.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }
}
