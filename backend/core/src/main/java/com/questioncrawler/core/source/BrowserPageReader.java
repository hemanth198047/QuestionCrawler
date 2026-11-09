package com.questioncrawler.core.source;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Reads pages through ONE browser window, the way a person would: the window opens the page, your company login applies
 * (one sign-in covers every site behind the same login), and the page's content is read after its scripts ran.
 *
 * <p>There is never more than one window. It is minimised while the app reads, comes to the front only when the
 * company login asks you to sign in, and closes by itself a few minutes after the last page. If you close it, pages that
 * need it are skipped until the next collection (no new window keeps popping up).
 */
public final class BrowserPageReader {

    private static final System.Logger LOG = System.getLogger("questioncrawler.browser");
    private static final Duration PAGE_WAIT = Duration.ofSeconds(45);
    private static final long IDLE_CLOSE_MINUTES = 3;

    private static volatile BrowserPageReader shared;

    private final BrowserLogin.Launcher launcher;
    private final Duration signInTimeout;
    private BrowserLogin.Session window;
    private boolean closedByYou;
    private long lastUse;
    private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "close-idle-browser");
        t.setDaemon(true);
        return t;
    });

    public BrowserPageReader(BrowserLogin.Launcher launcher, Duration signInTimeout) {
        this.launcher = launcher;
        this.signInTimeout = signInTimeout;
        closer.scheduleWithFixedDelay(this::closeIfIdle, 1, 1, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "close-reading-browser"));
    }

    /** The one reading window of the app (Edge/Chrome with the app's own profile, so your login is remembered). */
    public static synchronized BrowserPageReader shared(ConfluenceOptions.Browser options, Function<String, String> env) {
        if (shared == null) {
            shared = new BrowserPageReader(new BrowserLogin.ChromeLauncher(options, env), Duration.ofSeconds(options.timeoutSeconds()));
        }
        return shared;
    }

    /** A new collection or check: a window you closed last time may open again. */
    public synchronized void newRun() {
        closedByYou = false;
    }

    /** Reads one page through the window (signing in first when the company login asks for it). */
    public synchronized WebPageReader.WebPage read(URI url, String why) throws SourceException {
        if (closedByYou) {
            throw new SourceException(SourceException.Kind.FORBIDDEN, "Skipped: it needs the browser (" + why + "), and the browser window was closed. "
                    + "Collect again and leave the window open (it stays minimised) to read it.");
        }
        BrowserLogin.Session w = window();
        lastUse = System.nanoTime();
        try {
            BrowserLogin.PageView v = w.openPage(url.toString(), PAGE_WAIT);
            if (v == null) {
                throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser could not open " + Redirects.show(url));
            }
            boolean signedIn = false;
            if (BrowserLogin.CdpSession.onSignIn(v)) {
                v = waitForSignIn(w, url);
                signedIn = true;
                if (!sameDocument(v.url(), url.toString())) {
                    // after the login some sites land on their home page: open the page that was asked for
                    v = w.openPage(url.toString(), PAGE_WAIT);
                }
                if (BrowserLogin.CdpSession.onSignIn(v)) {
                    throw new SourceException(SourceException.Kind.FORBIDDEN, "The site still shows its sign-in page after signing in: " + Redirects.show(url));
                }
            }
            lastUse = System.nanoTime();
            if (v.html() == null || v.html().isBlank()) {
                throw new SourceException(SourceException.Kind.NOT_FOUND, "The page opened in the browser but had no content: " + Redirects.show(url));
            }
            return new WebPageReader.WebPage(url.toString(), v.url().isBlank() ? url.toString() : v.url(), v.html(), "text/html", List.of(),
                    signedIn ? "read through your browser after signing in" : "read through your browser");
        } catch (IOException e) {
            if (!w.alive()) {
                window = null;
                closedByYou = true;
                throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser window was closed while reading " + Redirects.show(url) + ".", null, e);
            }
            throw new SourceException(SourceException.Kind.TRANSIENT, "The browser could not load " + Redirects.show(url) + ": " + e.getMessage(), null, e);
        }
    }

    /** What one step of reading a results page returns: the links seen so far on it, and whether more were asked for. */
    public record Step(java.util.List<String> links, boolean advanced) {}

    /**
     * Opens a page of results (a Jive search, for example) in the window, signing in when asked, then repeatedly runs
     * {@code stepScript} (which lists the result links, scrolls to the end and clicks "More" / "Next" when there is one)
     * until no new links appear: the whole list, to the very end. Returns the links in the order found.
     */
    public synchronized java.util.List<String> collectLinks(URI url, String stepScript, int maxLinks, java.util.function.Consumer<String> progress)
            throws SourceException {
        if (closedByYou) {
            throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser window was closed. Collect again and leave the window open (it stays minimised).");
        }
        BrowserLogin.Session w = window();
        lastUse = System.nanoTime();
        try {
            BrowserLogin.PageView v = w.openPage(url.toString(), PAGE_WAIT);
            if (v == null) {
                throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser could not open " + Redirects.show(url));
            }
            if (BrowserLogin.CdpSession.onSignIn(v)) {
                v = waitForSignIn(w, url);
                if (!sameDocument(v.url(), url.toString())) {
                    w.openPage(url.toString(), PAGE_WAIT); // after the login: the search itself
                }
            }
            java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
            int quiet = 0;
            for (int step = 0; step < 400 && quiet < 4 && links.size() < maxLinks; step++) {
                String raw;
                try {
                    raw = w.run(stepScript, 30);
                } catch (IOException e) {
                    if (!w.alive()) {
                        throw e;
                    }
                    sleep(1500); // the page is changing (the next page of results is loading)
                    quiet++;
                    continue;
                }
                Step st = parseStep(raw);
                int before = links.size();
                links.addAll(st.links());
                boolean grew = links.size() > before;
                quiet = grew ? 0 : quiet + 1;
                if (!grew && !st.advanced() && quiet >= 2) {
                    break; // at the very end: nothing new and nothing more to load
                }
                if (progress != null && grew) {
                    progress.accept(links.size() + " result(s) so far");
                }
                lastUse = System.nanoTime();
                sleep(st.advanced() ? 2500 : 1500);
            }
            w.minimize();
            return new java.util.ArrayList<>(links).subList(0, Math.min(links.size(), maxLinks));
        } catch (IOException e) {
            if (!w.alive()) {
                window = null;
                closedByYou = true;
                throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser window was closed while reading " + Redirects.show(url) + ".", null, e);
            }
            throw new SourceException(SourceException.Kind.TRANSIENT, "The browser could not read " + Redirects.show(url) + ": " + e.getMessage(), null, e);
        }
    }

    private static Step parseStep(String raw) {
        java.util.List<String> links = new java.util.ArrayList<>();
        boolean advanced = false;
        try {
            com.fasterxml.jackson.databind.JsonNode n = com.questioncrawler.core.util.Json.MAPPER.readTree(raw == null ? "{}" : raw);
            n.path("links").forEach(l -> links.add(l.asText()));
            advanced = n.path("advanced").asBoolean(false);
        } catch (IOException | RuntimeException ignored) {
            // not a step result: nothing found this time
        }
        return new Step(links, advanced);
    }

    private BrowserLogin.PageView waitForSignIn(BrowserLogin.Session w, URI url) throws SourceException, IOException {
        LOG.log(System.Logger.Level.INFO, "Waiting for the company sign-in in the browser window for {0}", url.getHost());
        w.bringToFront();
        long until = System.nanoTime() + signInTimeout.toNanos();
        int fronted = 0;
        while (System.nanoTime() < until) {
            sleep(1500);
            if (!w.alive()) {
                window = null;
                closedByYou = true;
                throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser window was closed before signing in to " + url.getHost() + ".");
            }
            if (fronted++ == 2) {
                w.bringToFront(); // once more: windows opened by a background program can appear behind others
            }
            BrowserLogin.PageView v;
            try {
                v = w.currentPage();
            } catch (IOException e) {
                continue; // the login is moving between pages
            }
            if (v != null && v.complete() && !BrowserLogin.CdpSession.onSignIn(v) && !v.url().startsWith("about:")) {
                w.minimize();
                return v;
            }
        }
        throw new SourceException(SourceException.Kind.FORBIDDEN, "Nobody signed in within " + signInTimeout.toMinutes() + " minutes for " + url.getHost() + ".");
    }

    private BrowserLogin.Session window() throws SourceException {
        if (window != null && window.alive()) {
            return window;
        }
        try {
            window = launcher.open("about:blank");
        } catch (IOException e) {
            throw new SourceException(SourceException.Kind.FORBIDDEN, "The browser could not be opened: " + e.getMessage(), null, e);
        }
        window.minimize(); // it only comes to the front when you need to sign in
        return window;
    }

    private static boolean sameDocument(String a, String b) {
        return a != null && b != null && a.split("[?#]", 2)[0].replaceAll("/+$", "").equalsIgnoreCase(b.split("[?#]", 2)[0].replaceAll("/+$", ""));
    }

    private synchronized void closeIfIdle() {
        if (window != null && System.nanoTime() - lastUse > TimeUnit.MINUTES.toNanos(IDLE_CLOSE_MINUTES)) {
            close();
        }
    }

    public synchronized void close() {
        if (window != null) {
            try {
                window.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
            window = null;
        }
    }

    private static void sleep(long millis) throws SourceException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.FATAL, "Interrupted", null, e);
        }
    }
}
