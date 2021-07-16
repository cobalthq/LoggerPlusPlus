package com.nccgroup.loggerplusplus.exports;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.filter.logfilter.LogFilter;
import com.nccgroup.loggerplusplus.filter.parser.ParseException;
import com.nccgroup.loggerplusplus.logentry.LogEntry;
import com.nccgroup.loggerplusplus.logentry.LogEntryField;
import com.nccgroup.loggerplusplus.logentry.Status;
import com.nccgroup.loggerplusplus.util.Globals;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CobaltExporter extends AutomaticLogExporter implements ExportPanelProvider, ContextMenuExportProvider {

    CloseableHttpClient httpClient;
    ArrayList<LogEntry> pendingEntries;
    LogFilter logFilter;
    private List<LogEntryField> fields;
    private String indexName;
    private ScheduledFuture indexTask;
    private int connectFailedCounter;
    private final int UPLOAD_BATCH_SIZE = 50;

    private final ScheduledExecutorService executorService;
    private final CobaltExporterControlPanel controlPanel;

    private Logger logger = LogManager.getLogger(this);

    protected CobaltExporter(ExportController exportController, Preferences preferences) {
        super(exportController, preferences);
        this.fields = new ArrayList<>(preferences.getSetting(Globals.PREF_PREVIOUS_COBALT_FIELDS));
        executorService = Executors.newScheduledThreadPool(1);

        if ((boolean) preferences.getSetting(Globals.PREF_COBALT_AUTOSTART_GLOBAL)
                || (boolean) preferences.getSetting(Globals.PREF_COBALT_AUTOSTART_PROJECT)) {
            //Autostart exporter.
            try {
                this.exportController.enableExporter(this);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(LoggerPlusPlus.instance.getLoggerFrame(), "Could not start cobalt exporter: " +
                        e.getMessage() + "\nSee the logs for more information.", "Cobalt Exporter", JOptionPane.ERROR_MESSAGE);
                logger.error("Could not automatically start cobalt exporter:", e);
            }
        }
        controlPanel = new CobaltExporterControlPanel(this);
    }

    @Override
    void setup() throws Exception {
        if (this.fields == null || this.fields.isEmpty())
            throw new Exception("No fields configured for export.");

        String projectPreviousFilterString = preferences.getSetting(Globals.PREF_ELASTIC_FILTER_PROJECT_PREVIOUS);
        String filterString = preferences.getSetting(Globals.PREF_ELASTIC_FILTER);

        if (!Objects.equals(projectPreviousFilterString, filterString)) {
            //The current filter isn't what we used to export last time.
            int res = JOptionPane.showConfirmDialog(LoggerPlusPlus.instance.getLoggerFrame(),
                    "Heads up! Looks like the filter being used to select which logs to export to " +
                            "CobaltSearch has changed since you last ran the exporter for this project.\n" +
                            "Do you want to continue?", "CobaltSearch Export Log Filter", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (res == JOptionPane.NO_OPTION) {
                throw new Exception("Export cancelled.");
            }
        }

        if (!StringUtils.isBlank(filterString)) {
            try {
                logFilter = new LogFilter(exportController.getLoggerPlusPlus().getLibraryController(), filterString);
            } catch (ParseException ex) {
                logger.error("The log filter configured for the Cobalt exporter is invalid!", ex);
            }
        }

        httpClient = HttpClients.createDefault();


        pendingEntries = new ArrayList<>();
        int delay = preferences.getSetting(Globals.PREF_COBALT_DELAY);
        indexTask = executorService.scheduleWithFixedDelay(this::indexPendingEntries, delay, delay, TimeUnit.SECONDS);
        LoggerPlusPlus.callbacks.printOutput("Cobalt Logger++ initialized successfully");
    }

    @Override
    public void exportNewEntry(final LogEntry logEntry) {
        if (logEntry.getStatus() == Status.PROCESSED) {
            if (logFilter != null && !logFilter.matches(logEntry)) return;
            pendingEntries.add(logEntry);
        }
    }

    @Override
    public void exportUpdatedEntry(final LogEntry updatedEntry) {
        if (updatedEntry.getStatus() == Status.PROCESSED) {
            if (logFilter != null && !logFilter.matches(updatedEntry)) return;
            pendingEntries.add(updatedEntry);
        }
    }

    @Override
    void shutdown() throws Exception {
        if (this.indexTask != null) {
            indexTask.cancel(true);
        }
        this.pendingEntries = null;
    }

    @Override
    public JComponent getExportPanel() {
        return controlPanel;
    }

    @Override
    public JMenuItem getExportEntriesMenuItem(List<LogEntry> entries) {
        return null;
    }

    private void indexPendingEntries() {
        try {
            if (this.pendingEntries.size() == 0) return;

            ArrayList<LogEntry> entriesInBulk;
            synchronized (pendingEntries) {
                entriesInBulk = new ArrayList<>(pendingEntries);
                pendingEntries.clear();
            }

            String address = preferences.getSetting(Globals.PREF_COBALT_ADDRESS);
            Collection<List<LogEntry>> batchEntries = splitPendingEntries(entriesInBulk);
            HttpPost post = new HttpPost(address);

            for (List<LogEntry> entries : batchEntries) {
                try {
                    LoggerPlusPlus.callbacks.printOutput("Uploading 1 message with " + entries.size() + " log entries...");
                    post.setEntity(buildRequestBody((ArrayList<LogEntry>) entries));
                    CloseableHttpResponse response = httpClient.execute(post);
                    int statusCode = response.getStatusLine().getStatusCode();

                    LoggerPlusPlus.callbacks.printOutput("Upload finished with status code " + statusCode);
                    if (statusCode >= 400) {
                        LoggerPlusPlus.callbacks.printOutput(EntityUtils.toString(response.getEntity()));
                    }
                    connectFailedCounter = 0;
                } catch (ConnectException e) {
                    LoggerPlusPlus.callbacks.printError("Connection error, upload failed");
                    connectFailedCounter++;
                    if (connectFailedCounter > 5) {
                        JOptionPane.showMessageDialog(JOptionPane.getFrameForComponent(LoggerPlusPlus.instance.getLoggerMenu()),
                                "Cobalt exporter could not connect after 5 attempts. Elastic exporter shutting down...",
                                "Cobalt Exporter - Connection Failed", JOptionPane.ERROR_MESSAGE);
                        shutdown();
                    }
                } catch (Exception e) {
                    LoggerPlusPlus.callbacks.printError("Upload failed: " + e.getMessage());
                } finally {
                    post.releaseConnection();
                }
            }
        } catch (Exception e) {
            LoggerPlusPlus.callbacks.printError("Upload failed: " + ExceptionUtils.getStackTrace(e));
        }
    }

    private Collection<List<LogEntry>> splitPendingEntries(ArrayList<LogEntry> entriesInBulk) {
        AtomicInteger counter = new AtomicInteger();
        return entriesInBulk
                .stream()
                .collect(Collectors.groupingBy(it -> counter.getAndIncrement() / UPLOAD_BATCH_SIZE))
                .values();
    }

    private StringEntity buildRequestBody(ArrayList<LogEntry> entriesInBulk) throws UnsupportedEncodingException {
        JsonArray jsonArray = new JsonArray();
        for (LogEntry logEntry : entriesInBulk) {
            JsonObject jsonObject = new JsonObject();
            for (LogEntryField field : this.fields) {
                Object value = logEntry.getValueByKey(field);
                if (value != null) {
                    jsonObject.addProperty(field.getFullLabel(), formatValue(value));
                }
            }
            jsonArray.add(jsonObject);
        }

        StringEntity body = new StringEntity(jsonArray.toString());
        return body;
    }

    private String formatValue(Object value) {
        if (value instanceof java.net.URL) {
            return String.valueOf((java.net.URL) value);
        } else if (value instanceof String) {
            return (String) value;
        } else {
            return value.toString();
        }
    }

    public ExportController getExportController() {
        return this.exportController;
    }

    public List<LogEntryField> getFields() {
        return fields;
    }

    public void setFields(List<LogEntryField> fields) {
        preferences.setSetting(Globals.PREF_PREVIOUS_COBALT_FIELDS, fields);
        this.fields = fields;
    }
}
