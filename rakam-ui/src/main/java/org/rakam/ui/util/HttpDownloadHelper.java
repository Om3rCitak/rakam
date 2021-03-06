package org.rakam.ui.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collection;

public class HttpDownloadHelper {

    private boolean useTimestamp;
    private boolean skipExisting;

    public boolean download(URL source, Path dest, DownloadProgress progress) throws Exception {
        if (Files.exists(dest) && skipExisting) {
            return true;
        }

        //don't do any progress, unless asked
        if (progress == null) {
            progress = new NullProgress();
        }

        //set the timestamp to the file date.
        long timestamp = 0;

        boolean hasTimestamp = false;
        if (useTimestamp && Files.exists(dest) ) {
            timestamp = Files.getLastModifiedTime(dest).toMillis();
            hasTimestamp = true;
        }

        GetThread getThread = new GetThread(source, dest, hasTimestamp, timestamp, progress);

        try {
            getThread.setDaemon(true);
            getThread.start();
            getThread.join(50000);

            if (getThread.isAlive()) {
                throw new RuntimeException("The GET operation took longer than " + 50000 + ", stopping it.");
            }
        }
        catch (InterruptedException ie) {
            return false;
        } finally {
            getThread.closeStreams();
        }

        return getThread.wasSuccessful();
    }


    /**
     * Interface implemented for reporting
     * progress of downloading.
     */
    public interface DownloadProgress {
        /**
         * begin a download
         */
        void beginDownload();

        /**
         * tick handler
         */
        void onTick();

        /**
         * end a download
         */
        void endDownload();
    }

    /**
     * do nothing with progress info
     */
    public static class NullProgress implements DownloadProgress {
        @Override
        public void beginDownload() {

        }

        @Override
        public void onTick() {
        }

        @Override
        public void endDownload() {

        }
    }

    /**
     * verbose progress system prints to some output stream
     */
    public static class VerboseProgress implements DownloadProgress {
        private int dots;
        private PrintWriter writer;

        public VerboseProgress(PrintStream out) {
            this.writer = new PrintWriter(out);
        }


        public VerboseProgress(PrintWriter writer) {
            this.writer = writer;
        }

        @Override
        public void beginDownload() {
            writer.print("Downloading ");
            dots = 0;
        }

        @Override
        public void onTick() {
            writer.print(".");
            if (dots++ > 50) {
                writer.flush();
                dots = 0;
            }
        }

        @Override
        public void endDownload() {
            writer.println("DONE");
            writer.flush();
        }
    }

    private class GetThread extends Thread {

        private final URL source;
        private final Path dest;
        private final boolean hasTimestamp;
        private final long timestamp;
        private final DownloadProgress progress;

        private boolean success;
        private IOException ioexception;
        private InputStream is;
        private OutputStream os;
        private URLConnection connection;
        private int redirections;

        GetThread(URL source, Path dest, boolean h, long t, DownloadProgress p) {
            this.source = source;
            this.dest = dest;
            hasTimestamp = h;
            timestamp = t;
            progress = p;
        }

        @Override
        public void run() {
            try {
                success = get();
            } catch (IOException ioex) {
                ioexception = ioex;
            }
        }

        private boolean get() throws IOException {

            connection = openConnection(source);

            if (connection == null) {
                return false;
            }

            boolean downloadSucceeded = downloadFile();

            //if (and only if) the use file time option is set, then
            //the saved file now has its timestamp set to that of the
            //downloaded file
            if (downloadSucceeded && useTimestamp) {
                updateTimeStamp();
            }

            return downloadSucceeded;
        }


        private boolean redirectionAllowed(URL aSource, URL aDest) throws IOException {
            redirections++;
            if (redirections > 5) {
                String message = "More than " + 5 + " times redirected, giving up";
                throw new IOException(message);
            }


            return true;
        }

        private URLConnection openConnection(URL aSource) throws IOException {

            // set up the URL connection
            URLConnection connection = aSource.openConnection();
            // modify the headers
            // NB: things like user authentication could go in here too.
            if (hasTimestamp) {
                connection.setIfModifiedSince(timestamp);
            }

            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).setInstanceFollowRedirects(false);
                connection.setUseCaches(true);
                connection.setConnectTimeout(5000);
            }
            connection.setRequestProperty("User-Agent", "elasticsearch-plugin-manager");

            // connect to the remote site (may take some time)
            connection.connect();

            // First check on a 301 / 302 (moved) response (HTTP only)
            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection) connection;
                int responseCode = httpConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    String newLocation = httpConnection.getHeaderField("Location");
                    URL newURL = new URL(newLocation);
                    if (!redirectionAllowed(aSource, newURL)) {
                        return null;
                    }
                    return openConnection(newURL);
                }
                // next test for a 304 result (HTTP only)
                long lastModified = httpConnection.getLastModified();
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED
                        || (lastModified != 0 && hasTimestamp && timestamp >= lastModified)) {
                    // not modified so no file download. just return
                    // instead and trace out something so the user
                    // doesn't think that the download happened when it
                    // didn't
                    return null;
                }
                // test for 401 result (HTTP only)
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    String message = "HTTP Authorization failure";
                    throw new IOException(message);
                }
            }

            return connection;
        }

        private boolean downloadFile() throws IOException {
            IOException lastEx = null;
            for (int i = 0; i < 3; i++) {
                // this three attempt trick is to get round quirks in different
                // Java implementations. Some of them take a few goes to bind
                // property; we ignore the first couple of such failures.
                try {
                    is = connection.getInputStream();
                    break;
                } catch (IOException ex) {
                    lastEx = ex;
                }
            }
            if (is == null) {
                throw new IOException("Can't get " + source + " to " + dest, lastEx);
            }

            dest.toFile().mkdirs();
            os = Files.newOutputStream(dest);
            progress.beginDownload();
            boolean finished = false;
            try {
                byte[] buffer = new byte[1024 * 100];
                int length;
                while (!isInterrupted() && (length = is.read(buffer)) >= 0) {
                    os.write(buffer, 0, length);
                    progress.onTick();
                }
                finished = !isInterrupted();
            } finally {
                if (!finished) {
                    // we have started to (over)write dest, but failed.
                    // Try to delete the garbage we'd otherwise leave
                    // behind.
                    closeWhileHandlingException(Arrays.asList(os, is));
                    deleteFilesIgnoringExceptions(Arrays.asList(dest));
                } else {
                    os.close();
                    is.close();
                }
            }
            progress.endDownload();
            return true;
        }


        public void deleteFilesIgnoringExceptions(Collection<? extends Path> files) {
            for (Path name : files) {
                if (name != null) {
                    try {
                        Files.delete(name);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }

        public void closeWhileHandlingException(Iterable<? extends Closeable> objects) {
            for (Closeable object : objects) {
                try {
                    if (object != null) {
                        object.close();
                    }
                } catch (Throwable t) {
                }
            }
        }


        private void updateTimeStamp() throws IOException {
            long remoteTimestamp = connection.getLastModified();
            if (remoteTimestamp != 0) {
                Files.setLastModifiedTime(dest, FileTime.fromMillis(remoteTimestamp));
            }
        }

        boolean wasSuccessful() throws IOException {
            if (ioexception != null) {
                throw ioexception;
            }
            return success;
        }

        void closeStreams() throws IOException {
            interrupt();
            if (success) {
                is.close();
                os.close();
            } else {
                closeWhileHandlingException(Arrays.asList(is, os));
                if (dest != null && Files.exists(dest)) {
                    deleteFilesIgnoringExceptions(Arrays.asList(dest));
                }
            }
        }
    }
}
