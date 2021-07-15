package com.nccgroup.loggerplusplus.exports;

import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.nccgroup.loggerplusplus.logentry.LogEntry;
import com.nccgroup.loggerplusplus.logentry.LogEntryField;
import com.nccgroup.loggerplusplus.logentry.Status;
import com.nccgroup.loggerplusplus.util.Globals;
import org.apache.commons.io.IOUtils;
import org.apache.http.entity.StringEntity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"javax.management.*", "javax.swing.*", "javax.net.ssl.*"})
@PrepareForTest(CobaltExporter.class)
public class CobaltExporterTest {
    LogEntry logEntry = mock(LogEntry.class);
    Preferences preferences = mock(Preferences.class);
    ExportController exportController = mock(ExportController.class);
    CobaltExporter cobaltExporter;

    @Before
    public void setUp() {
        List<LogEntryField> fields = new ArrayList<LogEntryField>();
        fields.add(LogEntryField.QUERY);
        when(preferences.getSetting(Globals.PREF_PREVIOUS_COBALT_FIELDS)).thenReturn(fields);
        when(preferences.getSetting(Globals.PREF_COBALT_AUTOSTART_GLOBAL)).thenReturn(true);
        when(preferences.getSetting(Globals.PREF_COBALT_DELAY)).thenReturn(1);
        when(preferences.getSetting(Globals.PREF_COBALT_ADDRESS)).thenReturn("http://example.com");
        cobaltExporter = new CobaltExporter(exportController, preferences);
        when(logEntry.getStatus()).thenReturn(Status.PROCESSED);
    }

    @Test
    public void testbuildRequestBody() throws Exception {
        when(logEntry.getValueByKey(LogEntryField.QUERY)).thenReturn("a=1&b=1");
        List entries = new ArrayList<LogEntry>();
        entries.add(logEntry);
        StringEntity result = Whitebox.invokeMethod(cobaltExporter, "buildRequestBody", entries);
        String content = IOUtils.toString(result.getContent(), StandardCharsets.UTF_8);
        assertEquals("[{\"Request.Query\":\"a=1&b=1\"}]", content);
    }

    @Test
    public void testDoesNotThrowExceptionForNull() throws Exception {
        when(logEntry.getValueByKey(LogEntryField.QUERY)).thenReturn(null);
        List entries = new ArrayList<LogEntry>();
        entries.add(logEntry);
        StringEntity result = Whitebox.invokeMethod(cobaltExporter, "buildRequestBody", entries);
        String content = IOUtils.toString(result.getContent(), StandardCharsets.UTF_8);
        assertEquals("[{}]", content);
    }
}
